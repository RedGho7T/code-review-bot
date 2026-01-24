package com.groviate.telegramcodereviewbot.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.exception.AiNonRetryableException;
import com.groviate.telegramcodereviewbot.exception.AiReviewException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.service.AiChatGateway;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.MergeRequestRagContextProvider;
import com.groviate.telegramcodereviewbot.service.PromptTemplateService;
import com.groviate.telegramcodereviewbot.service.ReviewMetricsService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Цель теста: доказать, что @CircuitBreaker реально работает (AOP включен),
 * и что fallbackMethod analyzeCodeFallback вызывается.
 * <p>
 * Почему это не unit-тест:
 * - @CircuitBreaker работает через Spring AOP proxy.
 * - в unit-тесте (new CodeReviewService) proxy нет -> fallback не вызывается.
 */
@SpringBootTest(classes = CodeReviewServiceCircuitBreakerIntegrationTest.TestApp.class)
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.openai.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.openai.sliding-window-size=1",
        "resilience4j.circuitbreaker.instances.openai.minimum-number-of-calls=1",
        "resilience4j.circuitbreaker.instances.openai.failure-rate-threshold=1",
        "resilience4j.circuitbreaker.instances.openai.wait-duration-in-open-state=60s"
})
class CodeReviewServiceCircuitBreakerIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CodeReviewService.class)
    static class TestApp {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockitoBean
    PromptTemplateService promptTemplateService;
    @MockitoBean
    CodeReviewProperties props;
    @MockitoBean
    MergeRequestRagContextProvider ragContextProvider;
    @MockitoBean
    AiChatGateway aiChatGateway;

    @MockitoBean
    ReviewMetricsService metrics;

    @Autowired
    CodeReviewService codeReviewService;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        // Сбрасываем состояние CB между тестами
        circuitBreakerRegistry.circuitBreaker("openai").reset();

        // Чтобы тест был детерминированным: RAG выключаем
        when(props.isRagEnabled()).thenReturn(false);

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");
    }

    @Test
    @DisplayName("Если OpenAI падает -> вызывается analyzeCodeFallback и результат содержит openai_fallback=true")
    void givenOpenAiFailsWhenAnalyzeCodeThenFallbackResultReturned() {
        // 1) Проверяем, что bean действительно proxy -> иначе @CircuitBreaker не сработает
        assertThat(AopUtils.isAopProxy(codeReviewService))
                .as("CodeReviewService должен быть AOP proxy, иначе @CircuitBreaker/fallback не работают")
                .isTrue();

        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();
        List<MergeRequestDiff> diffs = List.of(oneDiff());

        // 2) Симулируем падение OpenAI
        when(aiChatGateway.ask("SYS", "USER"))
                .thenThrow(new AiReviewException("boom"));

        // 3) Первый вызов: exception -> fallback -> CodeReviewResult
        CodeReviewResult r1 = codeReviewService.analyzeCode(mr, diffs);

        assertThat(r1.getScore()).isZero();
        assertThat(r1.getSummary()).contains("OpenAI сейчас недоступен");
        assertThat(r1.getMetadata()).contains("openai_fallback=true");

        // 4) CircuitBreaker должен стать OPEN после 1 ошибки (по нашим test properties)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("openai");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 5) Второй вызов: уже OPEN -> OpenAI не вызывается, но fallback всё равно отдаёт результат
        CodeReviewResult r2 = codeReviewService.analyzeCode(mr, diffs);

        assertThat(r2.getSummary()).contains("OpenAI сейчас недоступен");
        assertThat(r2.getMetadata()).contains("openai_fallback=true");

        // 6) Ключевая проверка: gateway был вызван только один раз (вторая попытка заблокирована CB)
        verify(aiChatGateway, times(1)).ask("SYS", "USER");
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

    @Test
    @DisplayName("Если AiNonRetryableException -> fallback не маскирует, CB не открывается")
    void givenAiNonRetryableWhenAnalyzeCodeThenExceptionPropagatesAndCbStaysClosed() {
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();
        List<MergeRequestDiff> diffs = List.of(oneDiff());

        when(aiChatGateway.ask("SYS", "USER"))
                .thenThrow(new AiNonRetryableException("invalid api key"));

        assertThatThrownBy(() -> codeReviewService.analyzeCode(mr, diffs))
                .isInstanceOf(AiNonRetryableException.class);

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("openai");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        verify(aiChatGateway, times(1)).ask("SYS", "USER");
    }
}
