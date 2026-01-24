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

    private static final String METRIC_CODE_REVIEW_TOTAL = "code_review_total";
    private static final String METRIC_CODE_REVIEW_DURATION = "code_review_duration";
    private static final String TAG_RESULT = "result";

    private final MeterRegistry registry;

    private final Counter success;
    private final Counter failed;
    private final Counter skipped;

    private final Timer durationSuccess;
    private final Timer durationFailed;
    private final Timer durationSkipped;

    public ReviewMetricsService(MeterRegistry registry) {
        this.registry = registry;

        this.success = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, "success")
                .register(registry);

        this.failed = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, "failed")
                .register(registry);

        this.skipped = Counter.builder(METRIC_CODE_REVIEW_TOTAL)
                .tag(TAG_RESULT, "skipped")
                .register(registry);

        this.durationSuccess = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, "success")
                .register(registry);

        this.durationFailed = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, "failed")
                .register(registry);

        this.durationSkipped = Timer.builder(METRIC_CODE_REVIEW_DURATION)
                .tag(TAG_RESULT, "skipped")
                .register(registry);
    }

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
}