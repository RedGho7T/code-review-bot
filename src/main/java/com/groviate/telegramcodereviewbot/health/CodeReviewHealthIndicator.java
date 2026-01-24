package com.groviate.telegramcodereviewbot.health;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class CodeReviewHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CodeReviewHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Health health() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("openai");
        CircuitBreaker.State state = cb.getState();

        Health.Builder builder = (state == CircuitBreaker.State.OPEN)
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("openaiCircuitBreakerState", state.name())
                .withDetail("failureRate", cb.getMetrics().getFailureRate())
                .build();
    }
}