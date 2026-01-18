package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.entity.ReviewStatus;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.repository.ReviewStatusRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Главный оркестратор всего процесса ревью.
 * Координирует работу всех сервисов: получение MR, анализ кода, публикацию результатов, отслеживание статуса.
 */
@Service
@Slf4j
public class ReviewOrchestrator {

    private final CodeReviewProperties props;
    private final ReviewStatusRepository statusRepository;
    private final GitLabMergeRequestClient mrClient;
    private final CodeReviewService codeReviewService;
    private final GitLabCommentService commentService;
    private final ReviewOrchestrator self; //для асинхронного вызова

    public ReviewOrchestrator(CodeReviewProperties props,
                              ReviewStatusRepository statusRepository,
                              GitLabMergeRequestClient mrClient,
                              CodeReviewService codeReviewService,
                              GitLabCommentService commentService,
                              @Lazy ReviewOrchestrator self) {
        this.props = props;
        this.statusRepository = statusRepository;
        this.mrClient = mrClient;
        this.codeReviewService = codeReviewService;
        this.commentService = commentService;
        this.self = self;
    }

    /**
     * Точка входа из scheduler / webhook.
     * Проверяет включен ли бот.
     * Вызывает async версию через self.
     */
    public void enqueueReview(Integer projectId, Integer mrIid) {
        if (!props.isEnabled()) {
            log.info("Бот отключен: пропустить проверку для {}/{}", projectId, mrIid);
            return;
        }
        self.runReviewAsync(projectId, mrIid);
    }

    @Async("reviewExecutor")
    public void runReviewAsync(Integer projectId, Integer mrIid) {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        try {
            var mr = mrClient.getMergeRequest(projectId, mrIid);
            if (mr == null) return;

            if (!"opened".equalsIgnoreCase(mr.getStatus())) {
                log.info("[{}] MR не открыт, пропускаем: {}/{}", runId, projectId, mrIid);
                return;
            }
            if (Boolean.TRUE.equals(mr.getDraft()) || Boolean.TRUE.equals(mr.getWorkInProgress())) {
                log.info("[{}] MR черновик или не завершен: {}/{}", runId, projectId, mrIid);
                return;
            }

            String headSha = mr.getSha();
            if (headSha == null || headSha.isBlank()) {
                log.info("[{}] headSha отсутствует, пропускаем: {}/{}", runId, projectId, mrIid);
                return;
            }

            if (!self.tryMarkRunning(projectId, mrIid, headSha)) {
                log.info("[{}] Проверка уже проведена: {}/{}", runId, projectId, mrIid);
                return;
            }

            commentService.publishStatusComment(projectId, mrIid,
                    "[" + runId + "] Старт ревью. SHA=" + headSha);

            var diffs = mrClient.getChanges(projectId, mrIid);

            commentService.publishStatusComment(projectId, mrIid,
                    "[" + runId +
                            "] Анализируем изменения в (" + (diffs == null ? 0 : diffs.size()) + " файлов)…");

            CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

            commentService.publishStatusComment(projectId, mrIid,
                    "[" + runId + "] Публикую ревью (score=" + result.getScore() + ")…");

            commentService.publishReviewWithInline(projectId, mrIid, result, diffs);

            self.markSuccess(projectId, mrIid, headSha);

            commentService.publishStatusComment(projectId, mrIid,
                    "[" + runId + "] Готово. Score=" + result.getScore() + "/10");

        } catch (Exception e) {
            String err = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            self.markFailed(projectId, mrIid, err);
            commentService.publishStatusComment(projectId, mrIid,
                    "[" + runId + "] Ошибка ревью: " + truncate(err, 300));
            log.error("[{}] Ревью завершилось ошибкой для: {}/{}", runId, projectId, mrIid, e);
        }
    }

    @Transactional
    public boolean tryMarkRunning(Integer projectId, Integer mrIid, String headSha) {
        var status = statusRepository.findByProjectIdAndMrIid(projectId, mrIid)
                .orElseGet(() -> ReviewStatus.builder()
                        .projectId(projectId)
                        .mrIid(mrIid)
                        .headSha(headSha)
                        .attempts(0)
                        .status(ReviewStatus.ReviewState.PENDING)
                        .build());

        // уже успешно ревьюили этот SHA — пропускаем
        if (status.getStatus() == ReviewStatus.ReviewState.SUCCESS && headSha.equals(status.getHeadSha())) {
            return false;
        }

        // уже идёт ревью — пропускаем ТОЛЬКО если SHA тот же
        if (status.getStatus() == ReviewStatus.ReviewState.RUNNING
                && headSha.equals(status.getHeadSha())) {
            return false;
        }

        status.setStatus(ReviewStatus.ReviewState.RUNNING);
        status.setHeadSha(headSha);
        status.setAttempts(status.getAttempts() == null ? 1 : status.getAttempts() + 1);
        status.setLastError(null);
        status.setStartedAt(java.time.Instant.now());
        status.setFinishedAt(null);

        try {
            statusRepository.save(status);
        } catch (OptimisticLockException e) {
            log.warn("Данные в БД уже были изменены другим пользователем {}/{}", projectId, mrIid);
            return false;
        }

        return true;
    }

    @Transactional
    public void markSuccess(Integer projectId, Integer mrIid, String headSha) {
        statusRepository.findByProjectIdAndMrIid(projectId, mrIid).ifPresent(s -> {
            s.setStatus(ReviewStatus.ReviewState.SUCCESS);
            s.setHeadSha(headSha);
            s.setFinishedAt(java.time.Instant.now());
            s.setLastError(null);
            statusRepository.save(s);
        });
    }

    @Transactional
    public void markFailed(Integer projectId, Integer mrIid, String error) {
        statusRepository.findByProjectIdAndMrIid(projectId, mrIid).ifPresent(s -> {
            s.setStatus(ReviewStatus.ReviewState.FAILED);
            s.setLastError(truncate(error, 2000));
            s.setFinishedAt(java.time.Instant.now());
            statusRepository.save(s);
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
