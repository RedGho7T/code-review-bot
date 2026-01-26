package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.entity.ReviewStatus;
import com.groviate.telegramcodereviewbot.exception.CodeReviewBotException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.repository.ReviewStatusRepository;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.UUID;

/**
 * Главный оркестратор всего процесса ревью
 * <p>
 * Координирует работу всех сервисов и управляет жизненным циклом ревью:
 * <ol>
 *   <li>Получение информации о Merge Request из GitLab</li>
 *   <li>Фильтрация нецелевых MR (draft, closed, work in progress)</li>
 *   <li>Дедупликация по SHA коммита (не ревьюим один коммит дважды)</li>
 *   <li>Анализ кода через CodeReviewService (OpenAI + RAG)</li>
 *   <li>Публикация результатов в GitLab (общие и inline комментарии)</li>
 *   <li>Отслеживание статуса ревью в БД (ReviewStatus entity)</li>
 *   <li>Обработка ошибок с логированием и уведомлением в MR</li>
 * </ol>
 * <p>
 * Использует асинхронную обработку через @Async для неблокирования webhook.
 * <p>
 * Паттерн self-injection (@Lazy ReviewOrchestrator self):
 * Необходим для работы @Async и @Transactional аннотаций, так как Spring AOP работает
 * через proxy. При вызове метода через this - proxy не активируется, через self - активируется.
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
    private final ReviewMetricsService metrics;
    private static final Pattern ERROR_LINE = Pattern.compile("(?m)^error\\s*=\\s*(.+)$");

    /**
     * @param props             - свойства приложения (enabled flag, dry-run mode, лимиты)
     * @param statusRepository  - JPA репозиторий для сохранения статуса ревью в БД
     * @param mrClient          - REST клиент для работы с GitLab API (получение MR, diffs, публикация комментариев)
     * @param codeReviewService - сервис анализа кода через OpenAI (ChatClient + RAG контекст)
     * @param commentService    - сервис публикации результатов в GitLab (форматирование + GitLab API)
     * @param self              - @Lazy self-reference для вызова асинхронных и транзакционных методов
     */
    public ReviewOrchestrator(CodeReviewProperties props,
                              ReviewStatusRepository statusRepository,
                              GitLabMergeRequestClient mrClient,
                              CodeReviewService codeReviewService,
                              GitLabCommentService commentService,
                              @Lazy ReviewOrchestrator self,
                              ReviewMetricsService metrics) {
        this.props = props;
        this.statusRepository = statusRepository;
        this.mrClient = mrClient;
        this.codeReviewService = codeReviewService;
        this.commentService = commentService;
        this.self = self;
        this.metrics = metrics;
    }

    /**
     * Точка входа из scheduler / webhook.
     * Проверяет включен ли бот.
     * Вызывает async версию через self.
     */
    public void enqueueReview(Integer projectId, Integer mrIid) {
        if (!props.isEnabled()) {
            metrics.markSkipped(metrics.start());
            log.info("Бот отключен: пропустить проверку для {}/{}", projectId, mrIid);
            return;
        }
        self.runReviewAsync(projectId, mrIid);
    }

    /**
     * Асинхронное выполнение полного цикла ревью Merge Request
     * <p>
     * Выполняется в отдельном потоке. Может занимать 30-120 секунд.
     * <p>
     * 1. Получает MR из GitLab и проверяет статус (opened, не draft, не WIP)
     * 2. Получает SHA последнего коммита для дедупликации
     * 3. Пытается отметить начало ревью (если уже ревьюили SHA - выходит)
     * 4. Получает список измененных файлов
     * 5. Анализирует код через OpenAI (с RAG контекстом из ChromaDB)
     * 6. Публикует результаты в GitLab (общие + inline комментарии)
     * 7. Сохраняет статус SUCCESS в БД
     * <p>
     * При ошибке: логирует, сохраняет FAILED статус и публикует ошибку в MR.
     *
     * @param projectId - ID проекта в GitLab (например, 24)
     * @param mrIid     - ID Merge Request (например, 260)
     */
    @Async("reviewExecutor")
    public void runReviewAsync(Integer projectId, Integer mrIid) {
        Timer.Sample sample = metrics.start();
        String runId = UUID.randomUUID().toString().substring(0, 8);

        try {
            var mr = mrClient.getMergeRequest(projectId, mrIid);

            if (mr == null) {
                metrics.markSkipped(sample);
                return;
            }

            if (!"opened".equalsIgnoreCase(mr.getStatus())) {
                log.info("[{}] MR не открыт, пропускаем: {}/{}", runId, projectId, mrIid);
                metrics.markSkipped(sample);
                return;
            }

            if (Boolean.TRUE.equals(mr.getDraft()) || Boolean.TRUE.equals(mr.getWorkInProgress())) {
                log.info("[{}] MR черновик или не завершен: {}/{}", runId, projectId, mrIid);
                metrics.markSkipped(sample);
                return;
            }

            String headSha = mr.getSha();
            if (headSha == null || headSha.isBlank()) {
                log.info("[{}] headSha отсутствует, пропускаем: {}/{}", runId, projectId, mrIid);
                metrics.markSkipped(sample);
                return;
            }

            if (!self.tryMarkRunning(projectId, mrIid, headSha)) {
                log.info("[{}] Проверка уже проведена: {}/{}", runId, projectId, mrIid);
                metrics.markSkipped(sample);
                return;
            }

            safePublishStatus(projectId, mrIid, "[" + runId + "] Старт ревью. SHA=" + headSha);

            var diffs = mrClient.getChanges(projectId, mrIid);
            safePublishStatus(projectId, mrIid,
                    "[" + runId
                            + "] Анализируем изменения в (" + (diffs == null ? 0 : diffs.size()) + " файлов)…");

            CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

            if (isOpenAiFallback(result)) {
                String err = extractOpenAiError(result);

                self.markFailed(projectId, mrIid, err);
                metrics.markFailed(sample);

                safePublishStatus(projectId, mrIid,
                        "[" + runId + "] OpenAI недоступен — ревью не опубликовано. Причина: " + truncate(err, 300));

                return;
            }

            safePublishStatus(projectId, mrIid,
                    "[" + runId + "] Публикую ревью (score=" + result.getScore() + ")…");

            commentService.publishReviewWithInline(projectId, mrIid, result, diffs);

            self.markSuccess(projectId, mrIid, headSha);
            metrics.markSuccess(sample);

            safePublishStatus(projectId, mrIid,
                    "[" + runId + "] Готово. Score=" + result.getScore() + "/10");

        } catch (CodeReviewBotException e) {
            metrics.markFailed(sample);

            String err = e.getCode() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            self.markFailed(projectId, mrIid, err);

            safePublishStatus(projectId, mrIid,
                    "[" + runId + "] Ошибка ревью: " + truncate(err, 300));

            log.error("[{}] Ревью завершилось доменной ошибкой для: {}/{}", runId, projectId, mrIid, e);

        } catch (Exception e) {
            metrics.markFailed(sample);

            String err = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            self.markFailed(projectId, mrIid, err);

            safePublishStatus(projectId, mrIid,
                    "[" + runId + "] Ошибка ревью: " + truncate(err, 300));

            log.error("[{}] Ревью завершилось ошибкой для: {}/{}", runId, projectId, mrIid, e);
        }
    }

    /**
     * Атомарно пытается отметить что ревью началось
     * <p>
     * Обеспечивает дедупликацию по SHA и защиту от race condition.
     * <ol>
     *   <li>Находит или создает ReviewStatus для projectId + mrIid</li>
     *   <li>Проверяет: если SUCCESS + тот же SHA - уже ревьюили, возвращает false</li>
     *   <li>Проверяет: если RUNNING + тот же SHA - уже ревьюят, возвращает false</li>
     *   <li>Обновляет: status=RUNNING, headSha, attempts++, startedAt, очищает lastError</li>
     *   <li>Сохраняет в БД. При OptimisticLockException - возвращает false</li>
     * </ol>
     *
     * @param projectId - ID проекта в GitLab
     * @param mrIid     - Internal ID Merge Request
     * @param headSha   - SHA последнего коммита для дедупликации
     * @return true если можно продолжать ревью, false если нужно пропустить
     */
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

    /**
     * Атомарно отмечает успешное завершение ревью
     * <p>
     * Обновляет ReviewStatus: status=SUCCESS, headSha, finishedAt, очищает lastError.
     *
     * @param projectId - ID проекта
     * @param mrIid     - IID Merge Request
     * @param headSha   - SHA проревьюенного коммита
     */
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

    /**
     * Атомарно отмечает завершение ревью с ошибкой
     * <p>
     * Обновляет ReviewStatus: status=FAILED, lastError (обрезанный до 2000 символов), finishedAt.
     *
     * @param projectId - ID проекта
     * @param mrIid     - IID Merge Request
     * @param error     - описание ошибки (обычно ClassName: message)
     */
    @Transactional
    public void markFailed(Integer projectId, Integer mrIid, String error) {
        statusRepository.findByProjectIdAndMrIid(projectId, mrIid).ifPresent(s -> {
            s.setStatus(ReviewStatus.ReviewState.FAILED);
            s.setLastError(truncate(error, 2000));
            s.setFinishedAt(java.time.Instant.now());
            statusRepository.save(s);
        });
    }

    /**
     * Обрезает строку до максимальной длины с добавлением многоточия
     *
     * @param s   - строка для обрезания (может быть null)
     * @param max - максимальная длина
     * @return обрезанная строка с "…" если превышен лимит, null если входная строка null
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * Проверяет, был ли результат ревью сформирован через fallback (OpenAI недоступен / circuit breaker открыт)
     *
     * @param result результат анализа кода (может быть null)
     * @return true если в metadata присутствует маркер fallback, иначе false
     */
    private boolean isOpenAiFallback(CodeReviewResult result) {
        if (result == null) return false;
        String meta = result.getMetadata();
        return meta != null && meta.contains("openai_fallback=true");
    }

    /**
     * Извлекает краткое описание причины OpenAI fallback из {@link CodeReviewResult}
     *
     * @param result результат анализа кода (может быть null)
     * @return текст ошибки (никогда не null)
     */
    private String extractOpenAiError(CodeReviewResult result) {
        if (result == null) return "OpenAI fallback";

        String meta = result.getMetadata();
        if (meta != null) {
            Matcher m = ERROR_LINE.matcher(meta);
            if (m.find()) {
                return m.group(1).trim();
            }
        }

        // если в metadata нет строки error=..., берем summary как запасной вариант
        String summary = result.getSummary();
        return (summary == null || summary.isBlank()) ? "Ошибка обращения к OpenAI" : summary;
    }

    /**
     * Публикует служебный статус-комментарий в Merge Request, не прерывая основной процесс ревью
     *
     * @param projectId ID проекта в GitLab
     * @param mrIid     IID Merge Request
     * @param message   текст статусного сообщения
     */
    private void safePublishStatus(Integer projectId, Integer mrIid, String message) {
        try {
            commentService.publishStatusComment(projectId, mrIid, message);
        } catch (Exception ex) {
            log.warn("Не удалось опубликовать статус в GitLab для {}/{}: {}",
                    projectId, mrIid, ex.getMessage(), ex);
        }
    }
}
