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
            log.debug("RAG: найдено документов = {}", relevantDocs.size());

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
                String errBody = response.body() != null ? response.body().string() : "<empty>";
                log.warn("Chroma query failed: code={}, body={}", response.code(),
                        errBody.length() > 500 ? errBody.substring(0, 500) : errBody);
                throw new IOException("Chroma query failed: code=" + response.code());
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

        JsonNode ids = firstInnerArray(responseNode.path("ids"));
        JsonNode documents = firstInnerArray(responseNode.path("documents"));
        JsonNode distances = firstInnerArray(responseNode.path("distances"));
        JsonNode metadatas = firstInnerArray(responseNode.path("metadatas"));

        logRawSizes(ids, documents, distances, metadatas);

        int count = ids.size();
        if (count == 0) {
            return List.of();
        }

        double threshold = ragConfig.getSimilarityThreshold();
        RagDocument bestDoc = null;

        for (int i = 0; i < count; i++) {
            RagDocument doc = buildRagDocument(i, ids, documents, distances, metadatas);

            bestDoc = pickBest(bestDoc, doc);

            if (shouldInclude(threshold, doc.getSimilarity())) {
                results.add(doc);
            }
        }

        return ensureNonEmptyResults(results, bestDoc, threshold);
    }

    private void logRawSizes(JsonNode ids, JsonNode documents, JsonNode distances, JsonNode metadatas) {
        log.debug("RAG raw sizes: ids={}, docs={}, distances={}, metadatas={}",
                ids.size(),
                documents.size(),
                distances.size(),
                metadatas.size());
    }

    private RagDocument buildRagDocument(int i,
                                         JsonNode ids,
                                         JsonNode documents,
                                         JsonNode distances,
                                         JsonNode metadatas) {
        String id = ids.path(i).asText();
        String content = extractContent(documents, i);
        double distance = extractDistance(distances, i);
        double similarity = computeSimilarity(distance);
        String source = extractSource(metadatas, i);

        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setContent(content);
        doc.setSource(source);
        doc.setSimilarity(similarity);

        return doc;
    }

    /**
     * Безопасно извлекает содержимое документа из массива {@code documents}.
     *
     * @param documents массив документов (может быть не массивом)
     * @param i         индекс элемента
     * @return текст документа или пустая строка, если значение отсутствует
     */
    private String extractContent(JsonNode documents, int i) {
        return documents.isArray() ? documents.path(i).asText("") : "";
    }

    /**
     * Безопасно извлекает distance из массива {@code distances}.
     *
     * @param distances массив расстояний (может быть не массивом)
     * @param i         индекс элемента
     * @return distance или {@link Double#NaN}, если значение отсутствует
     */
    private double extractDistance(JsonNode distances, int i) {
        return distances.isArray() ? distances.path(i).asDouble(Double.NaN) : Double.NaN;
    }

    private String extractSource(JsonNode metadatas, int i) {
        String source = "unknown";
        if (metadatas.isArray() && metadatas.path(i).isObject()) {
            source = metadatas.path(i).path("source").asText("unknown");
        }
        return source;
    }

    /**
     * Выбирает лучший документ по similarity.
     *
     * @param bestDoc   текущий лучший документ (может быть null)
     * @param candidate кандидат на лучший документ
     * @return документ с максимальным similarity
     */
    private RagDocument pickBest(RagDocument bestDoc, RagDocument candidate) {
        if (bestDoc == null) {
            return candidate;
        }
        return candidate.getSimilarity() > bestDoc.getSimilarity() ? candidate : bestDoc;
    }

    /**
     * Проверяет, должен ли документ попасть в финальную выдачу с учётом порога similarity.
     * <p>
     * Если {@code threshold <= 0}, фильтрация отключается и включаются все документы.
     *
     * @param threshold  порог similarity из конфигурации
     * @param similarity similarity конкретного документа
     * @return true если документ следует включить в результат
     */
    private boolean shouldInclude(double threshold, double similarity) {
        // как было: threshold <= 0 — фильтр выключен
        return threshold <= 0.0 || similarity >= threshold;
    }

    /**
     * Гарантирует непустой результат поиска: если всё было отфильтровано по threshold,
     * добавляет лучший документ (bestDoc) как fallback.
     *
     * @param results   итоговый список после фильтрации
     * @param bestDoc   лучший кандидат по similarity (может быть null)
     * @param threshold порог similarity (используется для логирования)
     * @return список документов (может быть пустым, если кандидатов вообще не было)
     */
    private List<RagDocument> ensureNonEmptyResults(List<RagDocument> results,
                                                    RagDocument bestDoc,
                                                    double threshold) {
        if (!results.isEmpty() || bestDoc == null) {
            return results;
        }

        log.debug("RAG: all candidates below threshold={}, fallback to best similarity={}",
                threshold, bestDoc.getSimilarity());
        results.add(bestDoc);
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

    /**
     * Безопасно формирует короткое сообщение об ошибке:
     * <ul>
     *   <li> не возвращает null</li>
     *   <li> Если message отсутствует — возвращает имя класса</li>
     *   <li> Ограничивает длину сообщения до 200 символов</li>
     * </ul>
     *
     * @param t throwable (может быть null)
     * @return безопасное сообщение для логов/исключений
     */
    private static String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return (m.length() > 200) ? m.substring(0, 200) : m;
    }

    /**
     * Возвращает первый внутренний массив из структуры ответа ChromaDB.
     * <p>
     * ChromaDB часто возвращает результаты в виде вложенных массивов {@code [[...]]} (один query),
     * но иногда может быть уже плоский массив {@code [...]}. Метод нормализует оба случая.
     *
     * @param node узел JSON (может быть null/missing)
     * @return первый внутренний массив, плоский массив как есть
     */
    private static JsonNode firstInnerArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        // Если пришло [[...]] → вернём первый внутренний список
        if (node.isArray() && !node.isEmpty() && node.path(0).isArray()) {
            return node.path(0);
        }
        // Если пришло уже [...] → вернём как есть
        return node;
    }

    /**
     * Вычисляет similarity (0..1) по distance, возвращаемому ChromaDB.
     * <p>
     * Поддерживает два типовых случая:
     * <ul>
     *   <li>cosine distance в диапазоне 0..2 → similarity = 1 - distance</li>
     *   <li>прочие расстояния (например, L2) → similarity = 1 / (1 + distance)</li>
     * </ul>
     * Результат ограничивается диапазоном [0..1]. NaN/Infinity трактуются как 0.
     *
     * @param distance distance из ChromaDB
     * @return similarity в диапазоне 0..1
     */
    private static double computeSimilarity(double distance) {
        if (Double.isNaN(distance) || Double.isInfinite(distance)) {
            return 0.0;
        }

        if (distance >= 0.0 && distance <= 2.0) {
            double sim = 1.0 - distance;
            return Math.clamp(sim, 0.0, 1.0);
        }

        double sim = 1.0 / (1.0 + Math.max(0.0, distance));
        return Math.clamp(sim, 0.0, 1.0);
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

