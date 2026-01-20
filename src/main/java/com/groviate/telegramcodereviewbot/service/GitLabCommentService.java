package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiffRefs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
    private final InlineCommentPlannerService inlineCommentPlannerService;

    public GitLabCommentService(GitLabMergeRequestClient gitLabMergeRequestClient,
                                CommentFormatterService commentFormatterService,
                                CodeReviewProperties codeReviewProperties,
                                InlineCommentPlannerService inlineCommentPlannerService) {
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
        this.commentFormatterService = commentFormatterService;
        this.codeReviewProperties = codeReviewProperties;
        this.inlineCommentPlannerService = inlineCommentPlannerService;
    }

    /**
     * Публикует результат ревью в GitLab MR
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
     * @param lineNumber     - номер строки в файле где нужно оставить комментарий
     * @param commentText    - текст комментария (уже отформатированный в Markdown)
     * @throws GitlabClientException - если произойдёт ошибка
     */
    public void publishLineComment(Integer projectId,
                                   Integer mergeRequestId,
                                   MergeRequestDiffRefs refs,
                                   String oldPath,
                                   String newPath,
                                   Integer lineNumber,
                                   String commentText) {
        log.info("Публикуем inline-комментарий на строке {} в MR {}/{} ({})",
                lineNumber, projectId, mergeRequestId, newPath);

        if (codeReviewProperties.isDryRun()) {
            log.warn("DRY-RUN: inline не будет опубликован. {}", commentText);
            return;
        }

        try {
            gitLabMergeRequestClient.postLineComment(
                    projectId, mergeRequestId,
                    refs, oldPath, newPath,
                    lineNumber, commentText
            );
            log.info("Встроенный комментарий успешно опубликован на строке {} в MR {}/{}", lineNumber,
                    projectId, mergeRequestId);
        } catch (GitlabClientException e) {
            log.error("Ошибка при пубилкации встроенного комментария: {}", e.getMessage());

            throw e;
        }
    }

    /**
     * Публикует результат ревью с встроенными (inline) комментариями
     * <p>
     * Комбинированный подход:
     * <ol>
     *   <li>Публикует общий комментарий с результатами ревью (оценка, summary, suggestions)</li>
     *   <li>Получает последние diff refs для определения базового коммита</li>
     *   <li>Планирует inline комментарии (выбирает CRITICAL/WARNING по лимитам)</li>
     *   <li>Публикует inline комментарии с задержкой между ними (чтобы не спамить GitLab API)</li>
     *   <li>Логирует количество успешно опубликованных inline комментариев</li>
     * </ol>
     * <p>
     * Обрабатывает ошибки: если inline не удалось опубликовать - логирует warning и продолжает.
     * Общий комментарий все равно был опубликован, поэтому пользователь увидит результаты.
     *
     * @param projectId      - ID проекта в GitLab
     * @param mergeRequestId - ID Merge Request в проекте
     * @param reviewResult   - результат ревью с suggestions от OpenAI
     * @param diffs          - список MergeRequestDiff с информацией о файлах и изменениях
     */
    public void publishReviewWithInline(Integer projectId,
                                        Integer mergeRequestId,
                                        CodeReviewResult reviewResult,
                                        List<MergeRequestDiff> diffs) {

        // 1) общий итоговый комментарий
        publishReview(projectId, mergeRequestId, reviewResult);

        // 2) inline (если включено)
        if (!codeReviewProperties.isInlineEnabled()) {
            log.info("Inline comments disabled by config");
            return;
        }

        if (codeReviewProperties.isDryRun()) {
            log.warn("DRY-RUN: inline comments will not be published");
            return;
        }

        if (diffs == null || diffs.isEmpty()) {
            log.info("No diffs provided - skip inline comments");
            return;
        }

        // 3) спланировать что постить (ЯВНЫЙ тип, без var)
        List<InlineCommentPlannerService.InlineComment> planned =
                inlineCommentPlannerService.plan(reviewResult, diffs, codeReviewProperties);

        if (planned == null || planned.isEmpty()) {
            log.info("No inline comments planned");
            return;
        }

        // 4) получить diff refs
        MergeRequestDiffRefs refs = gitLabMergeRequestClient.getLatestDiffRefs(projectId, mergeRequestId);

        // 5) постим до N обсуждений
        int success = 0;

        int delayMs = codeReviewProperties.getInlinePublishDelayMs() == null
                ? 200
                : codeReviewProperties.getInlinePublishDelayMs();

        for (InlineCommentPlannerService.InlineComment c : planned) {
            try {
                publishLineComment(projectId, mergeRequestId,
                        refs,
                        c.oldPath(),
                        c.newPath(),
                        c.newLine(),
                        c.body());
                success++;
            } catch (Exception e) {
                // важно: не валим весь review из-за одного невалидного inline
                log.warn("Inline comment failed for {}:{} - {}",
                        c.newPath(), c.newLine(), e.getMessage());
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Inline comments published: {}/{}", success, planned.size());
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

        if (!codeReviewProperties.isStatusCommentsEnabled()) {
            log.debug("Status comments disabled, skip publishStatusComment");
            return;
        }

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
