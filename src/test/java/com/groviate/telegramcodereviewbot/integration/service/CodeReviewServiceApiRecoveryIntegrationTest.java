package com.groviate.telegramcodereviewbot.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.exception.AiReviewException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.service.AiChatGateway;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.MergeRequestRagContextProvider;
import com.groviate.telegramcodereviewbot.service.PromptTemplateService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Чекпоинт фазы 6: "Система восстанавливается после сбоев API".
 * <p>
 * Что доказываем:
 * 1) Retry: transient ошибки -> повторные попытки -> успех
 * 2) CircuitBreaker: при фейле OPEN -> блокирует вызов -> после wait-duration восстанавливается
 */
@SpringBootTest(classes = CodeReviewServiceApiRecoveryIntegrationTest.TestApp.class)
@TestPropertySource(properties = {
        // Быстрые ретраи, чтобы тесты выполнялись быстро
        "resilience4j.retry.instances.openai.max-attempts=3",
        "resilience4j.retry.instances.openai.wait-duration=10ms",

        // Делаем CB "агрессивным" и быстро восстанавливаемым
        "resilience4j.circuitbreaker.instances.openai.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.openai.sliding-window-size=1",
        "resilience4j.circuitbreaker.instances.openai.minimum-number-of-calls=1",
        "resilience4j.circuitbreaker.instances.openai.failure-rate-threshold=1",
        "resilience4j.circuitbreaker.instances.openai.permitted-number-of-calls-in-half-open-state=1",
        "resilience4j.circuitbreaker.instances.openai.wait-duration-in-open-state=200ms",
        "resilience4j.circuitbreaker.instances.openai.automatic-transition-from-open-to-half-open-enabled=true"
})
class CodeReviewServiceApiRecoveryIntegrationTest {

    enum Mode {
        FAIL_TWICE_THEN_SUCCESS,
        ALWAYS_FAIL,
        ALWAYS_SUCCESS
    }

    /**
     * Тестовый "фейковый OpenAI", чтобы мы могли детерминированно имитировать сбои.
     * Важно: метод public + @Retry -> Spring AOP реально делает повторные попытки.
     */
    static class FlakyAiGateway implements AiChatGateway {

        static final AtomicInteger calls = new AtomicInteger(0);
        static final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ALWAYS_SUCCESS);

        static void reset(Mode newMode) {
            calls.set(0);
            mode.set(newMode);
        }

        @Override
        @Retry(name = "openai")
        public String ask(String systemPrompt, String userPrompt) {
            int n = calls.incrementAndGet();

            return switch (mode.get()) {
                case FAIL_TWICE_THEN_SUCCESS -> {
                    if (n <= 2) {
                        throw new AiReviewException("transient openai error (attempt " + n + ")");
                    }
                    yield "{\"score\":8,\"summary\":\"ok\",\"suggestions\":[]}";
                }
                case ALWAYS_FAIL -> throw new AiReviewException("openai down");
                case ALWAYS_SUCCESS -> "{\"score\":8,\"summary\":\"ok\",\"suggestions\":[]}";
            };
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CodeReviewService.class)
    static class TestApp {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AiChatGateway aiChatGateway() {
            return new FlakyAiGateway();
        }
    }

    @MockitoBean
    PromptTemplateService promptTemplateService;
    @MockitoBean
    CodeReviewProperties props;
    @MockitoBean
    MergeRequestRagContextProvider ragContextProvider;

    @Autowired
    CodeReviewService codeReviewService;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        // выключаем RAG, чтобы тест не зависел от Chroma/RAG
        when(props.isRagEnabled()).thenReturn(false);

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        // сбрасываем состояние CB, чтобы тесты не влияли друг на друга
        circuitBreakerRegistry.circuitBreaker("openai").reset();
    }

    @Test
    @DisplayName("Recovery 1: OpenAI падает 2 раза -> Retry делает 3 попытки -> на 3-й успех, fallback НЕ используется")
    void givenTransientFailures_thenRetryRecoversAndReturnsSuccess() {
        FlakyAiGateway.reset(Mode.FAIL_TWICE_THEN_SUCCESS);

        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();
        List<MergeRequestDiff> diffs = List.of(oneDiff());

        CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

        // Успех: нормальный результат, не fallback
        assertThat(result.getScore()).isEqualTo(8);
        assertThat(result.getSummary()).isEqualTo("ok");
        assertThat(result.getMetadata()).doesNotContain("openai_fallback=true");

        // Доказываем retry: было 3 попытки
        assertThat(FlakyAiGateway.calls.get()).isEqualTo(3);

        // CB не должен быть OPEN, потому что итоговый вызов analyzeCode завершился успешно
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("openai");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Recovery 2: CB открывается на фейле -> затем после wait-duration пропускает успех и закрывается")
    @SuppressWarnings("java:S2925")
        // Thread.sleep в тестах допустим: ждём окно восстановления CB
    void givenCircuitBreakerOpens_thenAfterOpenWindowRecoversAndCloses() throws Exception {
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();
        List<MergeRequestDiff> diffs = List.of(oneDiff());

        // 1) Сначала "поломка OpenAI": analyzeCode упадёт -> сработает fallback -> CB станет OPEN
        FlakyAiGateway.reset(Mode.ALWAYS_FAIL);

        CodeReviewResult fallback1 = codeReviewService.analyzeCode(mr, diffs);
        assertThat(fallback1.getScore()).isZero();
        assertThat(fallback1.getMetadata()).contains("openai_fallback=true");

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("openai");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsAfterFail = FlakyAiGateway.calls.get();

        // 2) Пока CB OPEN — повторный вызов НЕ должен дергать OpenAI (метод блокируется)
        CodeReviewResult fallback2 = codeReviewService.analyzeCode(mr, diffs);
        assertThat(fallback2.getMetadata()).contains("openai_fallback=true");
        assertThat(FlakyAiGateway.calls.get()).isEqualTo(callsAfterFail);

        // 3) "API починилось": ждём окно OPEN и переключаем gateway на успех
        Thread.sleep(250);
        FlakyAiGateway.mode.set(Mode.ALWAYS_SUCCESS);

        CodeReviewResult ok = codeReviewService.analyzeCode(mr, diffs);

        // Успех после восстановления
        assertThat(ok.getScore()).isEqualTo(8);
        assertThat(ok.getMetadata()).doesNotContain("openai_fallback=true");

        // CB должен вернуться в CLOSED после успешного вызова в HALF_OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // После восстановления должен добавиться минимум 1 вызов к gateway (успешный)
        assertThat(FlakyAiGateway.calls.get()).isGreaterThan(callsAfterFail);
    }

    private static MergeRequestDiff oneDiff() {
        return MergeRequestDiff.builder()
                .oldPath("src/A.java")
                .newPath("src/A.java")
                .diff("+test")
                .newFile(false)
                .deletedFile(false)
                .renamedFile(false)
                .build();
    }
}
