package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.RagConfig;
import com.groviate.telegramcodereviewbot.exception.RagContextException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для поиска релевантного контекста из RAG
 * <p>
 * 1. Берет код из MR
 * 2. Конвертирует его в vector (embedding) через OpenAI
 * 3. Ищет похожие куски в ChromaDB
 * 4. Возвращает top-K релевантных стандартов
 */
@Service
@Slf4j
public class RagContextService {

    private static final String RAG_HEADER = "\n\n=== РЕЛЕВАНТНЫЕ СТАНДАРТЫ КОДИРОВАНИЯ ===\n\n";
    private static final String RAG_TRUNCATED = "...(RAG обрезан по лимиту)\n";
    private static final String DOC_TRUNCATED = "\n...(обрезано)\n";
    private static final MediaType JSON = MediaType.get("application/json");

    private final RagConfig ragConfig;
    private final EmbeddingModel embeddingModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile String codingStandardsCollectionId;


    public RagContextService(
            RagConfig ragConfig,
            EmbeddingModel embeddingModel,
            OkHttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.ragConfig = ragConfig;
        this.embeddingModel = embeddingModel;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Ищет релевантный контекст для кода
     *
     * @param code текст кода для анализа
     * @return строка с релевантными стандартами, готовая добавить в промпт
     */
    public String getContextForCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }

        try {
            int maxQ = ragConfig.getMaxEmbeddingQueryChars();
            if (maxQ <= 0) {
                maxQ = 20000;
            }

            String safeCode = (code.length() > maxQ) ? code.substring(0, maxQ) : code;

            float[] codeEmbedding = embeddingModel.embed(safeCode);

            List<RagDocument> relevantDocs = searchInChroma(codeEmbedding);
            if (relevantDocs.isEmpty()) {
                log.debug("Релевантные стандарты не найдены");
                return "";
            }

            return formatContextForPrompt(relevantDocs);

        } catch (Exception e) {
            log.debug("Ошибка при получении RAG контекста", e);
            throw new RagContextException("Failed to build RAG context: " + safeMsg(e), e);
        }
    }

    /**
     * Ищет в ChromaDB документы похожие на embeddings
     *
     * @param queryEmbedding вектор для поиска
     * @return список найденных документов
     */
    private List<RagDocument> searchInChroma(float[] queryEmbedding) throws IOException {
        Map<String, Object> queryPayload = new HashMap<>();
        queryPayload.put("query_embeddings", List.of(queryEmbedding));
        queryPayload.put("n_results", ragConfig.getTopK());
        queryPayload.put("include", List.of("documents", "distances", "metadatas"));

        String jsonPayload = objectMapper.writeValueAsString(queryPayload);

        String collectionId = getCodingStandardsCollectionId();

        Request request = new Request.Builder()
                .url(ragConfig.getUrl() + "/api/v1/collections/" + collectionId + "/query")
                .post(RequestBody.create(jsonPayload, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Ошибка при поиске в ChromaDB: httpCode={}", response.code());
                return List.of();
            }

            ResponseBody body = response.body();
            if (body == null) {
                log.warn("ChromaDB вернул пустой пустой ответ");
                return List.of();
            }

            JsonNode responseNode = objectMapper.readTree(body.string());
            return parseChromaResults(responseNode);
        }
    }

    /**
     * Получает ID коллекции "coding-standards" из ChromaDB
     * <p>
     * Если ID уже закэширован - возвращает его</li>
     * Иначе отправляет GET запрос в ChromaDB: /api/v1/collections/coding-standards</li>
     * Парсит JSON ответ и извлекает поле "id"</li>
     * Кеширует ID в volatile поле для последующих вызовов</li>
     * Возвращает ID</li>
     * <p>
     * Volatile поле обеспечивает thread-safety при параллельных вызовах.
     *
     * @return UUID коллекции "coding-standards" в ChromaDB
     * @throws IOException - если коллекция не найдена (код 404) или другая ошибка сети.
     *                     Сообщение исключения содержит код ответа и body для диагностики.
     */
    private String getCodingStandardsCollectionId() throws IOException {
        if (codingStandardsCollectionId != null && !codingStandardsCollectionId.isBlank()) {
            return codingStandardsCollectionId;
        }

        Request request = new Request.Builder()
                .url(ragConfig.getUrl() + "/api/v1/collections/coding-standards")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || body.isBlank()) {
                throw new IOException("Не удалось получить id коллекции coding-standards. code="
                        + response.code() + ", body=" + body);
            }

            JsonNode node = objectMapper.readTree(body);
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new IOException("Коллекция coding-standards вернула пустой id. body=" + body);
            }

            codingStandardsCollectionId = id;
            return id;
        }
    }

    /**
     * Парсит результаты поиска из ChromaDB в список RagDocument
     * <p>
     * Извлекает массивы ids, documents, distances, metadatas из responseNode</li>
     * Берет первый элемент каждого массива (так как query был один)</li>
     * Для каждого индекса создает RagDocument</li>
     * Вычисляет similarity = 1 - distance (косинусное расстояние -> сходство)</li>
     * Фильтрует по порогу similarityThreshold из конфигурации</li>
     * Возвращает список RagDocument</li>
     *
     * @param responseNode - JSON ответ от ChromaDB с результатами поиска
     * @return список RagDocument с полями: id, content, source, similarity.
     * Только документы с similarity >= similarityThreshold.
     */
    private List<RagDocument> parseChromaResults(JsonNode responseNode) {
        List<RagDocument> results = new ArrayList<>();

        JsonNode ids = responseNode.path("ids").path(0);
        JsonNode documents = responseNode.path("documents").path(0);
        JsonNode distances = responseNode.path("distances").path(0);
        JsonNode metadatas = responseNode.path("metadatas").path(0);

        int count = Math.min(ids.size(), Math.min(documents.size(), distances.size()));
        for (int i = 0; i < count; i++) {
            double distance = distances.path(i).asDouble();
            double similarity = 1 - distance;

            if (similarity < ragConfig.getSimilarityThreshold()) {
                continue;
            }

            RagDocument doc = new RagDocument();
            doc.setId(ids.path(i).asText());
            doc.setContent(documents.path(i).asText());
            doc.setSource(metadatas.path(i).path("source").asText("unknown"));
            doc.setSimilarity(similarity);

            results.add(doc);
        }

        return results;
    }

    /**
     * Форматирует список RagDocument в красивый текст для промпта
     * <p>
     * Соблюдает лимиты:
     * - maxRagCharsTotal: общий размер всего RAG блока
     * - maxRagCharsPerDoc: максимум символов на документ
     * - maxRagDocsPerSource: максимум документов с одного источника
     * - maxRagSources: максимум уникальных источников
     * <p>
     * Сортирует документы по similarity (DESC) - сначала наиболее похожие</li>
     * Инициализирует StringBuilder с заголовком "=== РЕЛЕВАНТНЫЕ СТАНДАРТЫ КОДИРОВАНИЯ ==="</li>
     * Для каждого документа вызывает processDocForPrompt() для добавления в результат</li>
     * processDocForPrompt() проверяет лимиты (per source, max sources, total chars)</li>
     * Если лимит превышен - обрезает и добавляет "...(RAG обрезан по лимиту)"</li>
     * Возвращает готовый текст</li>
     * <p>
     * Использует LinkedHashMap для подсчета документов per source с сохранением порядка вставки.
     *
     * @param documents - список RagDocument для форматирования
     * @return отформатированная строка со стандартами для добавления в промпт.
     */
    private String formatContextForPrompt(List<RagDocument> documents) {
        documents.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));

        int maxTotal = ragConfig.getMaxRagCharsTotal();
        int maxPerDoc = ragConfig.getMaxRagCharsPerDoc();
        int maxPerSource = ragConfig.getMaxRagDocsPerSource();
        int maxSources = ragConfig.getMaxRagSources();

        // source -> count, O(1) проверки/инкременты, порядок источников сохраняем
        Map<String, Integer> perSourceCount = new java.util.LinkedHashMap<>();

        StringBuilder sb = new StringBuilder(Math.min(maxTotal, 4096));
        sb.append(RAG_HEADER);

        for (RagDocument doc : documents) {
            if (processDocForPrompt(doc, perSourceCount, sb, maxTotal, maxPerDoc, maxPerSource, maxSources)) {
                break; // единственный break/continue в цикле
            }
        }

        log.debug("RAG: sources={}, totalChars={}", perSourceCount.size(), sb.length());
        return sb.toString();
    }

    /**
     * Обрабатывает один RagDocument для добавления в промпт
     * <p>
     * Проверяет лимиты и добавляет документ если возможно:
     * <ol>
     *   <li>Нормализует source (имя файла) через normalizeSource()</li>
     *   <li>Проверяет не превышен ли лимит maxRagSources (максимум источников)</li>
     *   <li>Проверяет не превышен ли лимит maxRagDocsPerSource для этого source</li>
     *   <li>Обрезает content до maxRagCharsPerDoc если необходимо через trimContent()</li>
     *   <li>Добавляет блок документа через appendDocBlock()</li>
     *   <li>Проверяет не превышен ли общий лимит maxRagCharsTotal</li>
     *   <li>Если превышен - откатывает изменения и добавляет "...(RAG обрезан по лимиту)"</li>
     *   <li>Обновляет счетчик документов per source</li>
     *
     * @param doc            - RagDocument для добавления
     * @param perSourceCount - Map source -> count для отслеживания количества документов per source
     * @param sb             - StringBuilder с накопленным результатом
     * @param maxTotal       - максимум общего размера RAG блока
     * @param maxPerDoc      - максимум символов на документ
     * @param maxPerSource   - максимум документов с одного source
     * @param maxSources     - максимум уникальных sources
     * @return true если документ был добавлен успешно (или частично с truncate),
     * false если достигнут лимит maxRagSources или maxTotal и нужно прервать обработку
     */
    private static boolean processDocForPrompt(RagDocument doc,
                                               Map<String, Integer> perSourceCount,
                                               StringBuilder sb,
                                               int maxTotal,
                                               int maxPerDoc,
                                               int maxPerSource,
                                               int maxSources) {

        String source = normalizeSource(doc.getSource());

        Integer currentCount = perSourceCount.get(source);
        boolean isNewSource = (currentCount == null);

        if (isNewSource && perSourceCount.size() >= maxSources) {
            return false; // пропускаем документ
        }

        int n = isNewSource ? 0 : currentCount;
        if (n >= maxPerSource) {
            return false; // пропускаем документ
        }

        // Важно: как и раньше, считаем документ "взятым" до проверки maxTotal
        perSourceCount.put(source, n + 1);

        String content = trimContent(doc.getContent(), maxPerDoc);

        int beforeLen = sb.length();
        appendDocBlock(sb, source, doc.getSimilarity(), content);

        if (sb.length() > maxTotal) {
            sb.setLength(beforeLen);
            sb.append(RAG_TRUNCATED);
            return true; // сигнал остановить цикл
        }

        return false;
    }

    /**
     * Нормализует имя источника (файла)
     * <p>
     * Если source пустой или null - возвращает "unknown".
     *
     * @param source - имя источника из метаданных ChromaDB
     * @return нормализованное имя источника
     */
    private static String normalizeSource(String source) {
        return (source == null || source.isBlank()) ? "unknown" : source;
    }

    /**
     * Обрезает содержимое документа до максимального размера
     * <p>
     * Если content превышает maxPerDoc - обрезает и добавляет DOC_TRUNCATED маркер.
     *
     * @param content   - содержимое документа
     * @param maxPerDoc - максимум символов на документ
     * @return обрезанный content если превышен лимит, иначе оригинал.
     */
    private static String trimContent(String content, int maxPerDoc) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (content.length() <= maxPerDoc) {
            return content;
        }
        return content.substring(0, maxPerDoc) + DOC_TRUNCATED;
    }

    /**
     * Добавляет блок документа в StringBuilder
     *
     * @param sb         - StringBuilder для накопления результата
     * @param source     - имя источника (файла)
     * @param similarity - similarity score (от 0.0 до 1.0)
     * @param content    - содержимое документа (уже обрезанное если необходимо)
     */
    private static void appendDocBlock(StringBuilder sb, String source, double similarity, String content) {
        sb.append("📚 ")
                .append(source)
                .append(" (подобие: ")
                .append(format2(similarity))
                .append("):\n")
                .append(content)
                .append("\n\n");
    }

    /**
     * Форматирует double значение в строку с 2 знаками после запятой
     * <p>
     * Использует ручное форматирование без String.format() для производительности.
     *
     * @param value - double значение для форматирования
     * @return строка с 2 знаками после запятой
     */
    private static String format2(double value) {
        long scaled = Math.round(value * 100.0);
        long intPart = scaled / 100;
        long frac = Math.abs(scaled % 100);
        return intPart + "." + (frac < 10 ? "0" : "") + frac;
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return (m.length() > 200) ? m.substring(0, 200) : m;
    }

    /**
     * DTO для внутреннего использования
     */
    @Data
    public static class RagDocument {
        private String id;
        private String content;
        private String source;
        private double similarity;
    }
}

