package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для индексирования RAG документов в ChromaDB
 * <p>
 * Работает при запуске приложения (реализует ApplicationRunner):
 * 1. Читает документы из resources/rag-documents/
 * 2. Разбивает на куски (chunks)
 * 3. Генерирует embeddings (векторные представления текста)
 * 4. Загружает в ChromaDB
 */
@Service
@Slf4j
public class DocumentIndexingService implements ApplicationRunner {

    private static final String CODING_STANDARDS_COLLECTION_NAME = "coding-standards";
    private static final MediaType JSON = MediaType.get("application/json");

    private final RagConfig ragConfig;
    private final EmbeddingModel embeddingModel;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private String codingStandardsCollectionId;

    /**
     * @param ragConfig      - конфигурация RAG (URL ChromaDB, параметры chunks)
     * @param embeddingModel - Spring AI модель для генерации embeddings (OpenAI text-embedding-ada-002)
     * @param httpClient     - OkHttp клиент для HTTP запросов в ChromaDB
     * @param objectMapper   - Jackson для JSON сериализации
     * @param resourceLoader - Spring ResourceLoader для загрузки документов из classpath
     */
    public DocumentIndexingService(
            RagConfig ragConfig,
            EmbeddingModel embeddingModel,  // Spring AI автоматически инжектирует это
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader
    ) {
        this.ragConfig = ragConfig;
        this.embeddingModel = embeddingModel;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    /**
     * ApplicationRunner точка входа - выполняется при старте приложения
     * <p>
     * Проверяет доступность ChromaDB, инициализирует коллекцию и индексирует документы.
     * При ошибке логирует warning и продолжает работу (RAG будет отключен).
     *
     * @param args - аргументы командной строки
     * @throws Exception - при критических ошибках (редко, в основном логируется)
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {

        if (!ragConfig.isIndexingEnabled()) {
            log.info("RAG indexing выключен (chroma.indexing-enabled=false), пропускаю индексирование");
            return;
        }

        log.info("Запуск индексирования RAG документов");

        try {
            if (!isChromaDbAvailable()) {
                log.warn("ChromaDb недоступен ({}), RAG отключен", ragConfig.getUrl());
                return;
            }

            //Создаем/очищаем коллекцию в ChromaDB
            initializeChromaCollection();
            // Индексируем документы из rag-documents/
            indexRagDocuments();

            log.info("RAG индексирование завершено успешно");
        } catch (Exception e) {
            log.warn("RAG индексирование завершилось с ошибкой", e);
        }
    }

    /**
     * Создает или получает существующую коллекцию "coding-standards" в ChromaDB
     * <p>
     * Процесс:
     * <ol>
     *   <li>POST /api/v1/collections с параметрами для создания</li>
     *   <li>Если коллекция уже существует (код 409) - получает ID через GET /api/v1/collections/{name}</li>
     *   <li>Сохраняет ID в поле codingStandardsCollectionId</li>
     * </ol>
     *
     * @throws IOException - при ошибке сети или работы с ChromaDB
     */
    private void initializeChromaCollection() throws IOException {
        log.info("Инициализирую коллекцию 'coding-standards' в ChromaDB");

        Map<String, Object> collectionData = new HashMap<>();
        collectionData.put("name", CODING_STANDARDS_COLLECTION_NAME);
        collectionData.put("metadata", Map.of("description", "Java coding standards and patterns"));

        String jsonBody = objectMapper.writeValueAsString(collectionData);

        Request request = new Request.Builder()
                .url(ragConfig.getUrl() + "/api/v1/collections")
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            // Успешное создание
            if (response.isSuccessful() && !body.isBlank()) {
                JsonNode node = objectMapper.readTree(body);
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    codingStandardsCollectionId = id;
                    log.info("Коллекция создана: id={}", id);
                    return;
                }
            }

            // Уже существует (409) -> берём по имени
            if (response.code() == 409) {
                String existingId = getCodingStandardsCollectionId();
                if (existingId != null && !existingId.isBlank()) {
                    codingStandardsCollectionId = existingId;
                    log.info("Коллекция уже существует: name={}, id={}", CODING_STANDARDS_COLLECTION_NAME, existingId);
                    return;
                }
            }

            throw new IOException("Не удалось создать/получить коллекцию coding-standards. code="
                    + response.code() + ", body=" + body);
        }
    }

    /**
     * Индексирует все RAG документы из classpath:rag-documents/
     * <p>
     * Документы:
     * - rag-documents/java-coding-standards.md
     * - rag-documents/spring-boot-patterns.md
     * - rag-documents/testing-standards.md
     * - rag-documents/security-guidelines.md
     * <p>
     * Для каждого документа: загружает и разбивает на chunks → добавляет в ChromaDB.
     *
     */
    private void indexRagDocuments() {
        log.info("Начинаю индексирование RAG документов");

        List<String> documentPaths = List.of(
                "rag-documents/java-coding-standards.md",
                "rag-documents/spring-boot-patterns.md",
                "rag-documents/testing-standards.md",
                "rag-documents/security-guidelines.md"
        );

        int totalChunks = 0;

        for (String documentPath : documentPaths) {
            try {
                String content = loadDocumentContent(documentPath);
                if (content == null) {
                    log.warn("Документ не найден: {}", documentPath);
                    continue;
                }
                log.info("Индексируется документ: {}", documentPath);

                List<String> chunks = splitIntoChunks(content,
                        ragConfig.getChunkSize(),
                        ragConfig.getChunkOverlap());

                log.info("  Разбит на {} кусков", chunks.size());

                // Загружаем куски в ChromaDB
                addChunksToChroma(documentPath, chunks);

                totalChunks += chunks.size();

            } catch (Exception e) {
                log.error("Ошибка при индексировании {}", documentPath, e);
            }
        }
        log.info("Всего индексировано {} кусков документов", totalChunks);
    }

    /**
     * Загружает содержимое документа из classpath:resources/
     *
     * @param resourcePath - путь документа (например, "rag-documents/my-file.md")
     * @return содержимое документа или null если не найден
     */
    private String loadDocumentContent(String resourcePath) {
        Resource resource = resourceLoader.getResource("classpath:" + resourcePath);
        if (!resource.exists()) {
            return null;
        }

        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось загрузить {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Разбивает большой документ на куски (chunks) с перекрытием
     *
     * @param content   текст для разбиения
     * @param chunkSize размер каждого куска
     * @param overlap   перекрытие между кусками
     * @return список кусков
     */
    private List<String> splitIntoChunks(String content, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        if (chunkSize <= 0) {
            chunks.add(content);
            return chunks;
        }

        int safeOverlap = Math.max(0, overlap);
        int step = Math.max(1, chunkSize - safeOverlap);

        for (int start = 0; start < content.length(); start += step) {
            int end = Math.min(start + chunkSize, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
        }

        return chunks;
    }

    /**
     * Загружает куски текста в ChromaDB
     * <p>
     * 1. Берет текст каждого куска
     * 2. Отправляет его в OpenAI API для создания embedding (вектора)
     * 3. Отправляет текст + вектор в ChromaDB
     * 4. ChromaDB индексирует это для быстрого поиска
     *
     * @param documentName имя документа (для метаданных)
     * @param chunks       список кусков текста
     */
    private void addChunksToChroma(String documentName, List<String> chunks) throws IOException {

        if (codingStandardsCollectionId == null || codingStandardsCollectionId.isBlank()) {
            throw new IllegalStateException("codingStandardsCollectionId не инициализирован. " +
                    "Сначала вызовите initializeChromaCollection()");
        }

        List<String> ids = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            String chunkId = documentName.replace("/", "-") + "-" + i;
            ids.add(chunkId);

            // Spring AI: embed(String) -> float[]
            float[] embedding = embeddingModel.embed(chunk);
            embeddings.add(embedding);

            documents.add(chunk);
            metadatas.add(Map.of(
                    "source", documentName,
                    "chunk_index", i
            ));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("ids", ids);
        payload.put("documents", documents);
        payload.put("embeddings", embeddings);
        payload.put("metadatas", metadatas);

        String jsonPayload = objectMapper.writeValueAsString(payload);

        Request request = new Request.Builder()
                .url(ragConfig.getUrl() + "/api/v1/collections/" + codingStandardsCollectionId + "/add")
                .post(RequestBody.create(jsonPayload, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody body = response.body();
                log.error("Ошибка при добавлении chunks в ChromaDB: code={}, body={}",
                        response.code(),
                        body != null ? body.string() : "<empty>");
                return;
            }
            log.debug("Добавлено {} chunks из документа {}", chunks.size(), documentName);
        }
    }

    /**
     * Проверка: работает ли ChromaDB
     *
     * @return true если ChromaDB доступен, false если нет
     */
    private boolean isChromaDbAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(ragConfig.getUrl() + "/api/v1/heartbeat")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean isAvailable = response.isSuccessful();
                log.info("ChromaDB доступен: {}", isAvailable);
                return isAvailable;
            }
        } catch (IOException e) {
            log.warn("ChromaDB не отвечает на heartbeat", e);
            return false;
        }
    }

    /**
     * Получает идентификатор (ID) коллекции {@value #CODING_STANDARDS_COLLECTION_NAME} из ChromaDB по имени.
     *
     * @return ID коллекции, если запрос успешен и поле {@code id} присутствует в ответе;
     * {@code null} если коллекция не найдена, ответ пустой или ChromaDB вернул неуспешный статус.
     * @throws IOException при сетевых ошибках или ошибках чтения/парсинга ответа.
     */
    private String getCodingStandardsCollectionId() throws IOException {
        Request request = new Request.Builder()
                .url(ragConfig.getUrl() + "/api/v1/collections/" + CODING_STANDARDS_COLLECTION_NAME)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || body.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            return node.path("id").asText(null);
        }
    }
}
