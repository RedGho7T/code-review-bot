package com.groviate.telegramcodereviewbot.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Сервис для сбора метрик по процессу code review.
 * <p>
 * Использует единые имена метрик {@code code_review_total} и {@code code_review_duration}
 * и тег {@code result} для разбиения по исходу операции.
 */
@Service
public class ReviewMetricsService {

    // --- Code review ---
    private static final String METRIC_CODE_REVIEW_TOTAL = "code_review_total";
    private static final String METRIC_CODE_REVIEW_DURATION = "code_review_duration";

    // --- RAG ---
    private static final String METRIC_RAG_CONTEXT_TOTAL = "rag_context_total";
    private static final String METRIC_RAG_CONTEXT_DURATION = "rag_context_duration";

    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_SKIPPED = "skipped";
    private static final String RESULT_EMPTY = "empty";

    private final MeterRegistry registry;

    // Code review counters
    private final Counter success;
    private final Counter failed;
    private final Counter skipped;

    // Code review timers
    private final Timer durationSuccess;
    private final Timer durationFailed;
    private final Timer durationSkipped;

    // RAG counters
    private final Counter ragSuccess;
    private final Counter ragEmpty;
    private final Counter ragFailed;
    private final Counter ragSkipped;

    // RAG timers
    private final Timer ragDurationSuccess;
    private final Timer ragDurationEmpty;
    private final Timer ragDurationFailed;
    private final Timer ragDurationSkipped;

    public ReviewMetricsService(MeterRegistry registry) {
        this.registry = registry;

        // Code review
        this.success = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, RESULT_SUCCESS)
                .register(registry);

        this.failed = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, RESULT_FAILED)
                .register(registry);

        this.skipped = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, RESULT_SKIPPED)
                .register(registry);

        this.durationSuccess = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, RESULT_SUCCESS)
                .register(registry);

        this.durationFailed = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, RESULT_FAILED)
                .register(registry);

        this.durationSkipped = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, RESULT_SKIPPED)
                .register(registry);

        // RAG
        this.ragSuccess = Counter.builder(METRIC_RAG_CONTEXT_TOTAL)
                .tag(TAG_RESULT, RESULT_SUCCESS)
                .register(registry);

        this.ragEmpty = Counter.builder(METRIC_RAG_CONTEXT_TOTAL)
                .tag(TAG_RESULT, RESULT_EMPTY)
                .register(registry);

        this.ragFailed = Counter.builder(METRIC_RAG_CONTEXT_TOTAL)
                .tag(TAG_RESULT, RESULT_FAILED)
                .register(registry);

        this.ragSkipped = Counter.builder(METRIC_RAG_CONTEXT_TOTAL)
                .tag(TAG_RESULT, RESULT_SKIPPED)
                .register(registry);

        this.ragDurationSuccess = Timer.builder(METRIC_RAG_CONTEXT_DURATION)
                .tag(TAG_RESULT, RESULT_SUCCESS)
                .register(registry);

        this.ragDurationEmpty = Timer.builder(METRIC_RAG_CONTEXT_DURATION)
                .tag(TAG_RESULT, RESULT_EMPTY)
                .register(registry);

        this.ragDurationFailed = Timer.builder(METRIC_RAG_CONTEXT_DURATION)
                .tag(TAG_RESULT, RESULT_FAILED)
                .register(registry);

        this.ragDurationSkipped = Timer.builder(METRIC_RAG_CONTEXT_DURATION)
                .tag(TAG_RESULT, RESULT_SKIPPED)
                .register(registry);
    }

    // Code review API

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void markSuccess(Timer.Sample sample) {
        success.increment();
        if (sample != null) sample.stop(durationSuccess);
    }

    public void markFailed(Timer.Sample sample) {
        failed.increment();
        if (sample != null) sample.stop(durationFailed);
    }

    public void markSkipped(Timer.Sample sample) {
        skipped.increment();
        if (sample != null) sample.stop(durationSkipped);
    }

    //RAG API

    public Timer.Sample startRag() {
        return Timer.start(registry);
    }

    public void markRagSuccess(Timer.Sample sample) {
        ragSuccess.increment();
        if (sample != null) sample.stop(ragDurationSuccess);
    }

    public void markRagEmpty(Timer.Sample sample) {
        ragEmpty.increment();
        if (sample != null) sample.stop(ragDurationEmpty);
    }

    public void markRagFailed(Timer.Sample sample) {
        ragFailed.increment();
        if (sample != null) sample.stop(ragDurationFailed);
    }

    public void markRagSkipped(Timer.Sample sample) {
        ragSkipped.increment();
        if (sample != null) sample.stop(ragDurationSkipped);
    }
}