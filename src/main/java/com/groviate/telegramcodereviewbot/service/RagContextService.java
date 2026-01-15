package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.RagConfig;
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
        if (code == null || code.trim().isEmpty()) {
            return "";
        }

        try {
            String safeCode = code;

            Integer maxQraw = ragConfig.getMaxEmbeddingQueryChars();
            int maxQ = (maxQraw != null && maxQraw > 0) ? maxQraw : 20000;

            if (safeCode.length() > maxQ) {
                safeCode = safeCode.substring(0, maxQ);
            }

            float[] codeEmbedding = embeddingModel.embed(safeCode);

            List<RagDocument> relevantDocs = searchInChroma(codeEmbedding);
            if (relevantDocs.isEmpty()) {
                log.debug("Релевантные стандарты не найдены");
                return "";
            }

            return formatContextForPrompt(relevantDocs);

        } catch (Exception e) {
            log.error("Ошибка при получении RAG контекста", e);
            return "";
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
                log.error("Ошибка при поиске в ChromaDB: {}", response.code());
                return List.of();
            }

            ResponseBody body = response.body();
            if (body == null) {
                log.error("ChromaDB вернул пустой body при query()");
                return List.of();
            }

            JsonNode responseNode = objectMapper.readTree(body.string());
            return parseChromaResults(responseNode);
        }
    }

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
     * Парсит результаты от ChromaDB в список документов
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
     * Форматирует список документов в красивую строку для промпта
     */
    private String formatContextForPrompt(List<RagDocument> documents) {
        documents.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));

        int maxTotal = ragConfig.getMaxRagCharsTotal();
        int maxPerDoc = ragConfig.getMaxRagCharsPerDoc();
        int maxPerSource = ragConfig.getMaxRagDocsPerSource();
        int maxSources = ragConfig.getMaxRagSources();

        Map<String, Integer> perSourceCount = new HashMap<>();
        List<String> usedSources = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== РЕЛЕВАНТНЫЕ СТАНДАРТЫ КОДИРОВАНИЯ ===\n\n");

        for (RagDocument doc : documents) {
            String source = doc.getSource();

            if (!usedSources.contains(source)) {
                if (usedSources.size() >= maxSources) continue;
                usedSources.add(source);
            }

            int n = perSourceCount.getOrDefault(source, 0);
            if (n >= maxPerSource) continue;
            perSourceCount.put(source, n + 1);

            String content = doc.getContent();
            if (content.length() > maxPerDoc) {
                content = content.substring(0, maxPerDoc) + "\n...(обрезано)\n";
            }

            String block = String.format(
                    "📚 %s (подобие: %.2f):\n%s\n\n",
                    source,
                    doc.getSimilarity(),
                    content
            );

            if (sb.length() + block.length() > maxTotal) {
                sb.append("...(RAG обрезан по лимиту)\n");
                break;
            }

            sb.append(block);
        }

        log.debug("RAG: sources={}, totalChars={}", usedSources.size(), sb.length());
        return sb.toString();
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

