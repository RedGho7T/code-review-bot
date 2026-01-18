package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.config.RagConfig;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Сервис для анализа кода через AI
 * <p>
 * Процесс работы:
 * 1. Получить MR и список измененных файлов
 * 2. Подготовить промпт для AI через PromptTemplateService
 * 3. Отправить промпт в ChatClient (Spring AI)
 * 4. ChatClient отправит промпт в OpenAI API
 * 5. GPT вернет ревью в JSON формате
 * 6. Парсим JSON в CodeReviewResult с помощью ObjectMapper
 * 7. Возвращаем результат
 */
@Service
@Slf4j
public class CodeReviewService {

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final RagContextService ragContextService;
    private final CodeReviewProperties codeReviewProperties;
    private final MergeRequestRagContextProvider ragContextProvider;

    /**
     *
     * @param chatClient            - клиент для общения с OpenAI API
     * @param promptTemplateService - сервис подготовки промптов из файлов
     * @param ragContextService     - сервис для получения RAG контекста
     * @param objectMapper          - Jackson для десериализации JSON
     */
    public CodeReviewService(ChatClient chatClient,
                             PromptTemplateService promptTemplateService,
                             ObjectMapper objectMapper,
                             RagContextService ragContextService,
                             CodeReviewProperties codeReviewProperties,
                             MergeRequestRagContextProvider ragContextProvider) {
        this.chatClient = chatClient;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        this.ragContextService = ragContextService;
        this.codeReviewProperties = codeReviewProperties;
        this.ragContextProvider = ragContextProvider;
        this.objectMapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Анализирует код в Merge Request через OpenAI
     *
     * @param mergeRequest - данные о MR (заголовок, описание, ID)
     * @param diffs        - список измённых файлов с кодом
     * @return результат анализа с оценкой и рекомендациями
     * @throws GitlabClientException - при ошибке
     */
    public CodeReviewResult analyzeCode(MergeRequest mergeRequest,
                                        List<MergeRequestDiff> diffs)
            throws GitlabClientException {

        if (mergeRequest == null) {
            throw new IllegalArgumentException("mergeRequest must not be null");
        }

        int filesCount = diffs == null ? 0 : diffs.size();

        log.info("Начинаем анализ кода через OpenAI");
        log.debug("MR: {}. Файлов: {}", mergeRequest.getId(), filesCount);

        try {
            if (filesCount == 0) {
                log.warn("MR {} не содержит измененных файлов", mergeRequest.getId());
                return createEmptyReview("Нет измененных файлов для анализа", 10);
            }

            // Если в конфигурации CodeReviewProperties выключено - RAG не будет вызван
            String ragContext = "";
            if (codeReviewProperties.isRagEnabled()) {
                ragContext = ragContextProvider.buildRagContext(diffs);
                log.info("RAG контекст получен, размер: {} символов", ragContext.length());
            } else {
                log.info("RAG отключен (code-review.rag-enabled=false), продолжаем без RAG");
            }

            log.debug("Подготавливаем промпт для AI");
            String prompt = promptTemplateService.preparePrompt(
                    diffs,
                    mergeRequest.getTitle(),
                    mergeRequest.getDescription() == null ? "" : mergeRequest.getDescription(),
                    ragContext
            );

            log.debug("Промпт подготовлен, размер: {} символов", prompt.length());

            log.info("Отправляем код на анализ в OpenAI");

            String response = chatClient
                    .prompt()
                    .system(promptTemplateService.getSystemPrompt())
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("OpenAI вернул пустой ответ для MR {}", mergeRequest.getId());
                return createEmptyReview("AI вернул пустой ответ", 0);
            }

            log.info("Ответ от OpenAI получен, размер: {} символов", response.length());
            log.debug("Парсим JSON ответ от OpenAI в CodeReviewResult");

            CodeReviewResult result = parseAiResponse(response);

            if (result == null) {
                throw new IllegalStateException("CodeReviewResult is null after parsing");
            }

            if (result.getAnalyzedAt() == null) {
                result.setAnalyzedAt(LocalDateTime.now());
            }

            log.info("Анализ успешно завершён. Оценка: {}/10", result.getScore());
            return result;

        } catch (Exception e) {
            log.error("Ошибка при анализе кода MR {}: {}", mergeRequest.getId(), e.getMessage(), e);
            throw new GitlabClientException("Ошибка при анализе: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит JSON ответ от OpenAI в объект CodeReviewResult
     *
     * @param jsonResponse - JSON ответ от GPT
     * @return объект CodeReviewResult с оценкой и рекомендациями
     */
    @SuppressWarnings("u")
    private CodeReviewResult parseAiResponse(String jsonResponse) {
        try {
            //Извлекаем JSON объект из ответа
            String normalized = extractJsonObject(jsonResponse);
            if (normalized == null || normalized.isBlank()) {
                return createEmptyReview("AI вернул пустой/некорректный JSON", 0);
            }

            CodeReviewResult result = objectMapper.readValue(normalized, CodeReviewResult.class);

            if (result.getSuggestions() == null) {
                result.setSuggestions(List.of());
            }
            return result;

        } catch (Exception e) {
            log.error("Ошибка при парсинге ответа AI: {}", e.getMessage(), e);
            return createEmptyReview("Ошибка при анализе", 0);
        }
    }

    /**
     * Создаёт пустой результат ревью
     *
     * @param summary - текст сообщения об ошибке
     * @param score   - оценка кода (0 - 10)
     * @return объект CodeReviewResult с пустым списком рекомендаций
     */
    private CodeReviewResult createEmptyReview(String summary, int score) {
        return CodeReviewResult.builder()
                .score(score)
                .summary(summary)
                .suggestions(List.of())
                .build();
    }

    /**
     * Возвращает JSON объект как String или null если не найден
     */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        int start = s.indexOf('{');
        if (start < 0) return null;

        int end = findJsonEnd(s, start);
        if (end < 0) return null;

        return s.substring(start, end + 1);
    }

    /**
     * Вспомогательный метод для поиска конца JSON объекта.
     */
    private int findJsonEnd(String s, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            // Обновляем состояние экранирования (вместо двух continue)
            escaped = updateEscapedState(c, escaped);

            // Если текущий символ экранирован, пропускаем его обработку
            if (escaped) {
                continue;
            }

            // Когда встречаем кавычку (вне экранирования), переключаем флаг
            if (c == '"') {
                inString = !inString;
            }

            // Если мы не внутри строки - обновляем глубину и проверяем конец
            if (!inString) {
                depth = updateDepth(depth, c);

                // Если глубина стала 0 - нашли закрывающую скобку JSON
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Управляет флагом экранирования символов.
     */
    private boolean updateEscapedState(char c, boolean wasEscaped) {
        // Если предыдущий символ был \, текущий был экранирован
        if (wasEscaped) {
            return false;
        }
        // Если текущий символ \, он экранирует СЛЕДУЮЩИЙ символ
        return c == '\\';
    }

    /**
     * Обновление глубины вложенности скобок.
     */
    private int updateDepth(int depth, char c) {
        if (c == '{') {
            return depth + 1;
        }
        if (c == '}') {
            return depth - 1;
        }
        return depth;
    }
}
