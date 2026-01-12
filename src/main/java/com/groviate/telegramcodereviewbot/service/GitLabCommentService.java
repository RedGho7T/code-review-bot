package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Сервис для публикации результатов ревью в GitLab
 * <p>
 * 1. Берет результат ревью из CodeReviewService (JSON)
 * 2. Форматирует его в Markdown через CommentFormatterService
 * 3. Публикует отформатированный текст в GitLab MR через GitLabMergeRequestClient
 * 4. Логирует результаты и обрабатывает ошибки
 */
@Service
@Slf4j
public class GitLabCommentService {

    private final GitLabMergeRequestClient gitLabMergeRequestClient;
    private final CommentFormatterService commentFormatterService;
    private final CodeReviewProperties codeReviewProperties;

    public GitLabCommentService(GitLabMergeRequestClient gitLabMergeRequestClient,
                                CommentFormatterService commentFormatterService,
                                CodeReviewProperties codeReviewProperties) {
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
        this.commentFormatterService = commentFormatterService;
        this.codeReviewProperties = codeReviewProperties;
    }

    /**
     * ОСНОВНОЙ МЕТОД: Публикует результат ревью в GitLab MR
     * <p>
     * 1. Форматируем результат ревью из JSON в Markdown через commentFormatterService
     * 2. Проверяем режим dry-run (для тестирования без публикации)
     * 3. Если не dry-run -> отправляем комментарий в GitLab через gitLabMergeRequestClient
     * 4. Логируем успех или ошибку
     *
     * @param projectId      - ID проекта в GitLab (например, 24)
     * @param mergeRequestId - ID Merge Request в проекте (например, 260)
     * @param reviewResult   - результат ревью из CodeReviewService с оценкой и suggestions
     * @throws GitlabClientException - если произойдёт ошибка при публикации в GitLab
     */
    public void publishReview(Integer projectId, Integer mergeRequestId, CodeReviewResult reviewResult) {

        log.info("Начинаем публикацию ревью для MR {}/{}", projectId, mergeRequestId);

        // Форматируем результат ревью в markdown
        String formattedComment = commentFormatterService.formatReview(reviewResult);

        log.debug("Результат ревью отформатирован в Markdown, размер: {} символов", formattedComment.length());

        //если dryRun = true -> публикация на gitlab не будет, только в консоль
        if (codeReviewProperties.isDryRun()) {
            log.warn("Комментарий не будет опубликован в Gitlab");
            log.warn("Содержание комментария: ");
            log.warn("\n{}", formattedComment);
            return;
        }

        try {
            gitLabMergeRequestClient.postComment(projectId, mergeRequestId, formattedComment);

            log.info("Ревью успешно опубликовано в MR {}/{}", projectId, mergeRequestId);
        } catch (GitlabClientException e) {
            log.error("Ошибка при публикации ревью в Gitlab, MR {}/{}, ошибка: {}", projectId,
                    mergeRequestId, e.getMessage());

            throw e;
        }
    }

    /**
     * Публикует комментарий к конкретной строке кода (встроенный комментарий)
     * <p>
     *
     * @param projectId      - ID проекта в GitLab
     * @param mergeRequestId - ID Merge Request
     * @param diffId         - ID конкретного diff (файла) в этом MR (GitLab присваивает ID каждому файлу)
     * @param lineNumber     - номер строки в файле где нужно оставить комментарий
     * @param commentText    - текст комментария (уже отформатированный в Markdown)
     * @throws GitlabClientException - если произойдёт ошибка
     */
    public void publishLineComment(Integer projectId, Integer mergeRequestId, Integer diffId,
                                   Integer lineNumber, String commentText) {
        log.info("Публикуем встроенный комментарий на строке {} в MR {}/{}/diff/{}", lineNumber, projectId,
                mergeRequestId, diffId);

        if (codeReviewProperties.isDryRun()) {
            log.warn("Встроенный комментарий к строке {} не будет опубликован", lineNumber);
            log.warn("Содержимое: {}", commentText);
            return;
        }

        try {
            gitLabMergeRequestClient.postLineComment(projectId, mergeRequestId, diffId, lineNumber, commentText);
            log.info("Встроенный комментарий успешно опубликован на строке {} в MR {}/{}", lineNumber,
                    projectId, mergeRequestId);
        } catch (GitlabClientException e) {
            log.error("Ошибка при пубилкации встроенного комментария: {}", e.getMessage());

            throw e;
        }
    }

    /**
     * Публикует комментарий со статусом анализа
     * <p>
     *
     * @param projectId      - ID проекта
     * @param mergeRequestId - ID MR
     * @param statusMessage  - сообщение о статусе (например, "Анализ в процессе...")
     */
    public void publishStatusComment(Integer projectId, Integer mergeRequestId, String statusMessage) {
        log.debug("Публикуем комментарий о статусе для MR {}/{}: {}", projectId, mergeRequestId, statusMessage);

        if (codeReviewProperties.isDryRun()) {
            log.warn("Комментарий о статусе не будет опубликован");
            return;
        }

        try {
            gitLabMergeRequestClient.postComment(projectId, mergeRequestId, statusMessage);
            log.info("Комментарий о статусе успешно опубликован");
        } catch (GitlabClientException e) {
            log.error("Ошибка при публикации комментария о статусе: {}", e.getMessage(), e);
        }
    }
}
