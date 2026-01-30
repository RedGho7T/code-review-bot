package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.dto.ReviewFinishedEvent;
import com.groviate.telegramcodereviewbot.entity.ReviewStatus;
import com.groviate.telegramcodereviewbot.exception.CodeReviewBotException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.repository.ReviewStatusRepository;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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
                              ReviewMetricsService metrics, ApplicationEventPublisher eventPublisher) {
        this.props = props;
        this.statusRepository = statusRepository;
        this.mrClient = mrClient;
        this.codeReviewService = codeReviewService;
        this.commentService = commentService;
        this.self = self;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
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
        ReviewRunContext ctx = new ReviewRunContext(projectId, mrIid, sample, shortRunId());

        try {
            executeReview(ctx);
        } catch (CodeReviewBotException e) {
            handleBotException(ctx, e);
        } catch (Exception e) {
            handleUnexpectedException(ctx, e);
        }
    }

    /**
     * Выполняет основной сценарий ревью MR.
     * <p>
     * Последовательно:
     * <ol>
     *   <li>Получает MR и отбрасывает нецелевые состояния (не opened / draft / WIP)</li>
     *   <li>Проверяет SHA и дедуплицирует ревью через {@link #tryMarkRunning(Integer, Integer, String)}</li>
     *   <li>Получает diffs, запускает AI-анализ и публикует результат</li>
     *   <li>Обновляет статус ревью в БД и метрики, публикует событие завершения</li>
     * </ol>
     * Исключения не перехватывает — они обрабатываются в {@link #runReviewAsync(Integer, Integer)}.
     *
     * @param ctx контекст текущего запуска ревью
     */
    private void executeReview(ReviewRunContext ctx) {

        var mr = mrClient.getMergeRequest(ctx.projectId, ctx.mrIid);
        if (mr == null) {
            metrics.markSkipped(ctx.sample);
            return;
        }

        ctx.mrTitle = safeText(mr.getTitle());
        ctx.mrUrl = safeText(mr.getWebUrl());

        if (!"opened".equalsIgnoreCase(mr.getStatus())) {
            log.info("[{}] MR не открыт, пропускаем: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid);
            metrics.markSkipped(ctx.sample);
            return;
        }

        if (Boolean.TRUE.equals(mr.getDraft()) || Boolean.TRUE.equals(mr.getWorkInProgress())) {
            log.info("[{}] MR черновик или не завершен: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid);
            metrics.markSkipped(ctx.sample);
            return;
        }

        String headSha = mr.getSha();
        if (headSha == null || headSha.isBlank()) {
            log.info("[{}] headSha отсутствует, пропускаем: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid);
            metrics.markSkipped(ctx.sample);
            return;
        }

        if (!self.tryMarkRunning(ctx.projectId, ctx.mrIid, headSha)) {
            log.info("[{}] Проверка уже проведена: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid);
            metrics.markSkipped(ctx.sample);
            return;
        }

        safePublishStatus(ctx.projectId, ctx.mrIid, "[" + ctx.runId + "] Старт ревью. SHA=" + headSha);

        var diffs = mrClient.getChanges(ctx.projectId, ctx.mrIid);
        ctx.diffsCount = diffs == null ? 0 : diffs.size();

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] Анализируем изменения в (" + ctx.diffsCount + " файлов)…");

        CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

        if (isOpenAiFallback(result)) {
            handleOpenAiFallback(ctx, extractOpenAiError(result));
            return;
        }

        int score = result.getScore() != null ? result.getScore() : 0;

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] Публикую ревью (score=" + score + ")…");

        commentService.publishReviewWithInline(ctx.projectId, ctx.mrIid, result, diffs);

        self.markSuccess(ctx.projectId, ctx.mrIid, headSha);
        metrics.markSuccess(ctx.sample);

        publishFinishedEvent(ctx, score, true);

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] Готово. Score=" + score + "/10");
    }

    /**
     * Обрабатывает сценарий fallback, когда OpenAI недоступен или circuit breaker открыт.
     * <p>
     * Помечает ревью как FAILED, фиксирует метрики и публикует статус в MR.
     * Ревью/inline-комментарии при этом не публикуются.
     *
     * @param ctx контекст текущего запуска ревью
     * @param err описание причины fallback (будет обрезано для статуса)
     */
    private void handleOpenAiFallback(ReviewRunContext ctx, String err) {
        self.markFailed(ctx.projectId, ctx.mrIid, err);
        metrics.markFailed(ctx.sample);

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] OpenAI недоступен — ревью не опубликовано. Причина: "
                        + truncate(err, 300));

        publishFinishedEvent(ctx, 0, false);
    }

    /**
     * Обрабатывает доменную ошибку ревью ({@link CodeReviewBotException}).
     * <p>
     * Обновляет статус в БД, фиксирует метрики, публикует статус-комментарий в MR
     * и отправляет событие завершения.
     *
     * @param ctx контекст текущего запуска ревью
     * @param e  доменное исключение (с кодом и сообщением)
     */
    private void handleBotException(ReviewRunContext ctx, CodeReviewBotException e) {
        metrics.markFailed(ctx.sample);

        String err = e.getCode() + ": " + safeText(e.getMessage());
        self.markFailed(ctx.projectId, ctx.mrIid, err);

        publishFinishedEvent(ctx, 0, false);

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] Ошибка ревью: " + truncate(err, 300));

        log.error("[{}] Ревью завершилось доменной ошибкой для: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid, e);
    }

    /**
     * Обрабатывает непредвиденную ошибку выполнения ревью.
     * <p>
     * Обновляет статус в БД, фиксирует метрики, публикует статус-комментарий в MR
     * и отправляет событие завершения.
     *
     * @param ctx контекст текущего запуска ревью
     * @param e  исключение (любое, кроме {@link CodeReviewBotException})
     */
    private void handleUnexpectedException(ReviewRunContext ctx, Exception e) {
        metrics.markFailed(ctx.sample);

        String err = e.getClass().getSimpleName() + ": " + safeText(e.getMessage());
        self.markFailed(ctx.projectId, ctx.mrIid, err);

        publishFinishedEvent(ctx, 0, false);

        safePublishStatus(ctx.projectId, ctx.mrIid,
                "[" + ctx.runId + "] Ошибка ревью: " + truncate(err, 300));

        log.error("[{}] Ревью завершилось ошибкой для: {}/{}", ctx.runId, ctx.projectId, ctx.mrIid, e);
    }

    /**
     * Публикует событие завершения ревью в Spring ApplicationEventPublisher.
     * <p>
     * Используется для внешних уведомлений/аналитики (например, логирование, телеметрия).
     *
     * @param ctx       контекст текущего запуска ревью
     * @param score     итоговый score (0..10)
     * @param published true если ревью было опубликовано в MR, иначе false
     */
    private void publishFinishedEvent(ReviewRunContext ctx, int score, boolean published) {
        eventPublisher.publishEvent(new ReviewFinishedEvent(
                ctx.runId,
                ctx.projectId,
                ctx.mrIid,
                ctx.mrTitle,
                ctx.mrUrl,
                score,
                ctx.diffsCount,
                published
        ));
    }

    /**
     * Null-safe нормализация строки: превращает null в пустую строку.
     *
     * @param value исходная строка (может быть null)
     * @return непустая ссылка на строку ("" если вход был null).
     */
    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Генерирует короткий идентификатор запуска ревью для логов и статусных сообщений.
     *
     * @return строка длиной 8 символов
     */
    private static String shortRunId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class ReviewRunContext {
        private final Integer projectId;
        private final Integer mrIid;
        private final Timer.Sample sample;
        private final String runId;

        private String mrTitle = "";
        private String mrUrl = "";
        private int diffsCount = 0;

        private ReviewRunContext(Integer projectId, Integer mrIid, Timer.Sample sample, String runId) {
            this.projectId = projectId;
            this.mrIid = mrIid;
            this.sample = sample;
            this.runId = runId;
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
