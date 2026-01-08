package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

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

    /**
     *
     * @param chatClient            - клиент для общения с OpenAI API
     * @param promptTemplateService - сервис подготовки промптов из файлов
     * @param objectMapper          - Jackson для десериализации JSON
     */
    public CodeReviewService(ChatClient chatClient,
                             PromptTemplateService promptTemplateService,
                             ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }

    /**
     * Анализирует код в Merge Request через OpenAI
     *
     * @param mergeRequest - данные о MR (заголовок, описание, ID)
     * @param diffs        - список измённых файлов с кодом
     * @return результат анализа с оценкой и рекомендациями
     * @throws GitlabClientException - при ошибке
     */
    public CodeReviewResult analyzeCode(MergeRequest mergeRequest, List<MergeRequestDiff> diffs) {
        try {
            log.info("Начинаем анализ кода в MR: {} ({})", mergeRequest.getTitle(), mergeRequest.getId());

            if (diffs == null || diffs.isEmpty()) {

                log.warn("MR {} не содержит измененных файлов", mergeRequest.getId());
                return createEmptyReview("Нет измененных файлов для анализа", 10);
            }
            // Берём шаблон из файлов и подставляем реальные данные
            String userPrompt = promptTemplateService.preparePrompt(
                    diffs,
                    mergeRequest.getTitle(),
                    mergeRequest.getDescription() != null ? mergeRequest.getDescription() : ""
            );
            log.debug("Промт подготовлен, размер: {} символов", userPrompt.length());

            //Отправляем запрос в Open AI
            String aiResponse = chatClient
                    // Начинаем построение запроса
                    .prompt()
                    // Добавляем системную инструкцию
                    .system(promptTemplateService.getSystemPrompt())
                    // Добавляем пользовательский промпт с кодом
                    .user(userPrompt)
                    // Отправляем запрос в OpenAI API
                    .call()
                    // Получаем результат
                    .content();

            if (aiResponse == null) {
                log.warn("AI вернул пустой ответ (NULL) для MR {}", mergeRequest.getId());
                aiResponse = "";
            }

            log.debug("Получен ответ от AI, размер: {} символов", aiResponse.length());

            CodeReviewResult result = parseAiResponse(aiResponse);

            log.info("Анализ завершен, оценка {}/10, предложений: {}",
                    result.getScore(),
                    result.getSuggestions().size());
            return result;

        } catch (Exception e) {
            log.error("Ошибка при анализе MR {} : {}", mergeRequest.getId(), e.getMessage());

            throw new GitlabClientException("Ошибка при анализе кода", e);
        }
    }

    /**
     * Парсит JSON ответ от OpenAI в объект CodeReviewResult
     *
     * @param jsonResponse - JSON ответ от GPT
     * @return объект CodeReviewResult с оценкой и рекомендациями
     */
    private CodeReviewResult parseAiResponse(String jsonResponse) {
        try {
            //Извлекаем JSON объект из ответа
            String normalized = extractJsonObject(jsonResponse);

            normalized = normalized.replace("\"suggestedFix\"", "\"suggestionFix\"");

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
     * Извлекает первый JSON объект вида {...} из текста
     *
     * @param text - текст для поиска JSON
     * @return JSON объект в виде строки, или исходный текст если JSON не найден
     */
    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
