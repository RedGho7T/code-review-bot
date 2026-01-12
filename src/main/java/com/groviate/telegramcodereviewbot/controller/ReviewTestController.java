package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.CodeSuggestion;
import com.groviate.telegramcodereviewbot.model.ReviewCategory;
import com.groviate.telegramcodereviewbot.model.SuggestionSeverity;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.CommentFormatterService;
import com.groviate.telegramcodereviewbot.service.GitLabCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Контроллер для ТЕСТИРОВАНИЯ Фазы 3: Публикация результатов ревью
 * <p>
 * - GET /api/test/review/comment/{projectId}/{mrId} - Опубликовать тестовый комментарий
 * - GET /api/test/review/format - Посмотреть в каком формате будет ревью (без публикации)
 * - POST /api/test/review/analyze/{projectId}/{mrId} - Проанализировать MR и опубликовать ревью
 *
 */
@RestController
@Slf4j
@RequestMapping("/api/test/review")
public class ReviewTestController {

    private final GitLabCommentService gitLabCommentService;
    private final CommentFormatterService commentFormatterService;
    private final CodeReviewService codeReviewService;
    private final GitLabMergeRequestClient gitLabMergeRequestClient;

    public ReviewTestController(GitLabCommentService gitLabCommentService,
                                CommentFormatterService commentFormatterService,
                                CodeReviewService codeReviewService,
                                GitLabMergeRequestClient gitLabMergeRequestClient) {
        this.gitLabCommentService = gitLabCommentService;
        this.commentFormatterService = commentFormatterService;
        this.codeReviewService = codeReviewService;
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
    }

    @PostMapping("/comment/{projectId}/{mrId}")
    public Map<String, Object> testPublishComment(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId) {

        log.info("Тест: Публикация тестового комментария в MR {}/{}", projectId, mrId);

        try {
            // ШАГ 1: Создаём ПОДДЕЛЬНЫЙ результат ревью
            CodeReviewResult fakeResult = createFakeReviewResult();

            log.info("Создан поддельный результат ревью: оценка {}/10", fakeResult.getScore());

            // ШАГ 2: Публикуем результат в GitLab
            gitLabCommentService.publishReview(projectId, mrId, fakeResult);

            log.info("Тестовый комментарий опубликован успешно");

            // ШАГ 3: Возвращаем результат тестирования
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Тестовый комментарий успешно опубликован в MR " + projectId + "/" + mrId);
            response.put("score", fakeResult.getScore());
            response.put("summary", fakeResult.getSummary());

            return response;

        } catch (Exception e) {
            log.error("Ошибка при тестировании: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Ошибка: " + e.getMessage());
            errorResponse.put("error_type", e.getClass().getSimpleName());

            return errorResponse;
        }
    }

    /**
     * ENDPOINT 2: Посмотреть как форматируется ревью (для дебагинга)
     * <p>
     * Цель: Проверить форматирование Markdown БЕЗ публикации в GitLab
     * Полезно для отладки форматирования
     * <p>
     * Команда для теста:
     * GET http://localhost:8080/api/test/review/format
     * <p>
     * Ответ: Вернёт сам Markdown текст (можно скопировать и посмотреть как выглядит)
     *
     * @return Markdown текст форматированного комментария
     */
    @GetMapping("/format")
    public Map<String, String> testFormatting() {
        log.info("Тест: Форматирование ревью (без публикации)");

        try {
            // Создаём поддельный результат
            CodeReviewResult fakeResult = createFakeReviewResult();

            // Форматируем в Markdown
            String formattedComment = commentFormatterService.formatReview(fakeResult);

            log.info("Markdown форматирование выполнено успешно, размер: {} символов",
                    formattedComment.length());

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("markdown", formattedComment);
            response.put("size", String.valueOf(formattedComment.length()));

            return response;

        } catch (Exception e) {
            log.error("Ошибка при форматировании: {}", e.getMessage(), e);

            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "Ошибка: " + e.getMessage());

            return errorResponse;
        }
    }

    /**
     * Полный цикл - Анализ MR через AI + Публикация результата
     * <p>
     * Цель: Проверить полную цепочку Фазы 1-2-3
     * <p>
     * Что происходит:
     * 1. Получаем MR из GitLab (Фаза 1)
     * 2. Получаем diffs из GitLab (Фаза 1)
     * 3. Отправляем код в AI для анализа (Фаза 2)
     * 4. Получаем результат от AI (Фаза 2)
     * 5. Форматируем результат в Markdown (Фаза 3)
     * 6. Публикуем комментарий в GitLab (Фаза 3)
     * <p>
     * Команда для теста:
     * POST http://localhost:8080/api/test/review/analyze/24/288
     * <p>
     * ⚠️ Требует: Чтобы MR существовала в GitLab и был код для анализа
     *
     * @param projectId ID проекта в GitLab
     * @param mrId      ID Merge Request
     * @return JSON с информацией о результате анализа
     */
    @PostMapping("/analyze/{projectId}/{mrId}")
    public Map<String, Object> testFullReviewCycle(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId) {

        log.info("Тест: Полный цикл анализа и публикации MR {}/{}", projectId, mrId);

        Map<String, Object> response = new HashMap<>();

        try {
            // ШАГ 1: Получаем MR из GitLab (Фаза 1)
            log.info("Шаг 1: Получение MR...");
            var mergeRequest = gitLabMergeRequestClient.getMergeRequest(projectId, mrId);
            log.info("MR получена: {}", mergeRequest.getTitle());

            // ШАГ 2: Получаем diffs (Фаза 1)
            log.info("Шаг 2: Получение diffs...");
            var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);
            log.info("Получено {} файлов", diffs.size());

            // ШАГ 3: Анализируем код (Фаза 2)
            log.info("Шаг 3: Анализ кода AI...");
            CodeReviewResult reviewResult = codeReviewService.analyzeCode(mergeRequest, diffs);
            log.info("Анализ завершён, оценка: {}/10", reviewResult.getScore());

            // ШАГ 4: Публикуем результат (Фаза 3)
            log.info("Шаг 4: Публикация результата в GitLab...");
            gitLabCommentService.publishReview(projectId, mrId, reviewResult);
            log.info("Результат опубликован");

            response.put("status", "success");
            response.put("message", "Полный цикл анализа завершён успешно");
            response.put("mr_title", mergeRequest.getTitle());
            response.put("files_count", diffs.size());
            response.put("score", reviewResult.getScore());
            response.put("summary", reviewResult.getSummary());

        } catch (Exception e) {
            log.error("Ошибка в полном цикле анализа: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Ошибка: " + e.getMessage());
            response.put("error_type", e.getClass().getSimpleName());
        }

        return response;
    }

    /**
     * Создаёт ПОДДЕЛЬНЫЙ результат ревью для тестирования
     * <p>
     * Зачем нужен:
     * - Когда тестируем форматирование или публикацию, но не хотим ждать AI анализа
     * - Когда нет реальной MR для анализа
     * - Для быстрого цикла разработки
     * <p>
     * Результат включает:
     * - Оценку 8/10
     * - Несколько suggestions разных категорий и серьёзностей
     * - Реалистичный summary
     *
     * @return сгенерированный CodeReviewResult для тестирования
     */
    private CodeReviewResult createFakeReviewResult() {
        // Создаём список suggestions (предложений)
        List<CodeSuggestion> suggestions = new ArrayList<>();

        // Добавляем несколько тестовых suggestions
        suggestions.add(CodeSuggestion.builder()
                .message("Добавь Javadoc для публичного метода getUser()")
                .category(ReviewCategory.OTHER)
                .severity(SuggestionSeverity.INFO)
                .build());

        suggestions.add(CodeSuggestion.builder()
                .message("Используй try-with-resources для закрытия потоков")
                .category(ReviewCategory.OTHER)
                .severity(SuggestionSeverity.WARNING)
                .build());

        suggestions.add(CodeSuggestion.builder()
                .message("Валидируй ввод перед обработкой")
                .category(ReviewCategory.SECURITY)
                .severity(SuggestionSeverity.CRITICAL)
                .build());

        suggestions.add(CodeSuggestion.builder()
                .message("Используй константы вместо magic numbers")
                .category(ReviewCategory.CODE_STYLE)
                .severity(SuggestionSeverity.WARNING)
                .build());

        suggestions.add(CodeSuggestion.builder()
                .message("Добавь unit тесты для края случаев")
                .category(ReviewCategory.OTHER)
                .severity(SuggestionSeverity.INFO)
                .build());

        // Создаём результат ревью с помощью Builder pattern
        return CodeReviewResult.builder()
                .score(8)
                .summary("Хороший код с улучшениями. Основная логика реализована корректно, но есть замечания по безопасности и тестированию.")
                .suggestions(suggestions)
                .analyzedAt(LocalDateTime.now())
                .metadata("Проанализировано 2 файла, 127 строк кода")
                .build();
    }

    /**
     * Тест встроенного комментария на конкретной строке кода
     * <p>
     * Команда для теста:
     * POST http://localhost:8080/api/test/review/line-comment/24/288/1/5?text=Fix%20this
     *
     * @param projectId  ID проекта
     * @param mrId       ID MR
     * @param diffId     ID файла (diff)
     * @param lineNumber номер строки
     * @param text       текст комментария
     */
    @PostMapping("/line-comment/{projectId}/{mrId}/{diffId}/{lineNumber}")
    public Map<String, Object> publishLineComment(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId,
            @PathVariable Integer diffId,
            @PathVariable Integer lineNumber,
            @RequestParam String text) {

        log.info("Тестируем встроенный комментарий на строке {}", lineNumber);

        try {
            gitLabCommentService.publishLineComment(
                    projectId,
                    mrId,
                    diffId,
                    lineNumber,
                    text
            );

            return Map.of(
                    "status", "success",
                    "message", "Встроенный комментарий опубликован",
                    "projectId", projectId,
                    "mrId", mrId,
                    "lineNumber", lineNumber,
                    "text", text
            );
        } catch (Exception e) {
            log.error("Ошибка при публикации встроенного комментария: {}", e.getMessage());
            return Map.of(
                    "status", "error",
                    "message", "❌ " + e.getMessage()
            );
        }
    }

    /**
     * Тест комментария со статусом анализа
     * <p>
     * Команда для теста:
     * POST http://localhost:8080/api/test/review/status/24/288?message=Анализ%20в%20процессе
     *
     * @param projectId ID проекта
     * @param mrId      ID MR
     * @param message   сообщение о статусе
     */
    @PostMapping("/status/{projectId}/{mrId}")
    public Map<String, Object> publishStatusComment(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId,
            @RequestParam String message) {

        log.info("🔄 Тестируем комментарий о статусе");

        try {
            gitLabCommentService.publishStatusComment(projectId, mrId, message);

            return Map.of(
                    "status", "success",
                    "message", "Комментарий о статусе опубликован",
                    "projectId", projectId,
                    "mrId", mrId,
                    "statusMessage", message
            );
        } catch (Exception e) {
            log.error("Ошибка при публикации комментария о статусе: {}", e.getMessage());
            return Map.of(
                    "status", "error",
                    "message", "❌ " + e.getMessage()
            );
        }
    }
}
