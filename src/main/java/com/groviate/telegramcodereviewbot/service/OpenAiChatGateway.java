package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.exception.AiNonRetryableException;
import com.groviate.telegramcodereviewbot.exception.AiReviewException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Шлюз для обращения к OpenAI через Spring AI {@link ChatClient}.
 * <p>
 * Non-retryable ошибки (обычно 4xx, кроме 408/429, и NonTransientAiException) пробрасываются как
 * {@link AiNonRetryableException}, чтобы не влиять на circuit breaker и не маскировать проблемы запроса.
 * Остальные ошибки считаются временными и оборачиваются в {@link AiReviewException}.
 */
@Service
@Slf4j
public class OpenAiChatGateway implements AiChatGateway {

    private final ChatClient chatClient;

    public OpenAiChatGateway(ChatClient chatClient) {
        this.chatClient = chatClient;
    }


    /**
     * Выполняет запрос к OpenAI и возвращает текстовый ответ модели.
     * <p>
     * Метод обёрнут {@link Retry} (name = {@code openai}). Ошибки классифицируются:
     * <ul>
     *   <li>{@link NonTransientAiException} → {@link AiNonRetryableException}</li>
     *   <li>HTTP 4xx (кроме 408/429) → {@link AiNonRetryableException}</li>
     *   <li>HTTP 408/429, 5xx и прочие ошибки → {@link AiReviewException} (retryable)</li>
     * </ul>
     *
     * @param systemPrompt системный промпт (инструкции модели)
     * @param userPrompt   пользовательский промпт (контент/контекст анализа)
     * @return текстовый ответ модели (content)
     * @throws AiNonRetryableException если запрос отклонён (non-retryable ошибка)
     * @throws AiReviewException       если произошла временная/ретраимая ошибка обращения к OpenAI
     */
    @Override
    @Retry(name = "openai")
    public String ask(String systemPrompt, String userPrompt) {
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

        } catch (NonTransientAiException e) {
            throw new AiNonRetryableException("OpenAI non-transient error: " + safeMsg(e), e);

        } catch (HttpClientErrorException e) {
            // Ошибки 4xx от клиента (RestTemplate сценарии)
            if (isNonRetryable4xx(e.getStatusCode().value())) {
                throw new AiNonRetryableException("OpenAI rejected request (HTTP "
                        + e.getStatusCode().value() + ")", e);
            }
            // 429/408 и т.д. — ретраим
            throw new AiReviewException("OpenAI transient client error (HTTP " + e.getStatusCode().value() + ")", e);

        } catch (WebClientResponseException e) {
            // Ошибки 4xx/5xx
            int code = e.getStatusCode().value();
            if (isNonRetryable4xx(code)) {
                throw new AiNonRetryableException("OpenAI rejected request (HTTP " + code + ")", e);
            }
            throw new AiReviewException("OpenAI error (HTTP " + code + ")", e);

        } catch (Exception e) {
            // Всё остальное (timeouts, IO, 5xx без статуса и т.д.) — ретраимое
            throw new AiReviewException("Ошибка обращения к OpenAI", e);
        }
    }

    /**
     * Определяет, относится ли HTTP статус к non-retryable клиентским ошибкам (4xx), которые не ретраим.
     *
     * @param statusCode HTTP статус-код
     * @return true если ошибка non-retryable (4xx кроме 408/429)
     */
    private boolean isNonRetryable4xx(int statusCode) {
        if (statusCode < 400 || statusCode >= 500) {
            return false;
        }
        return statusCode != 429 && statusCode != 408;
    }

    /**
     * Безопасно формирует короткое сообщение об ошибке для логов/исключений:
     *
     * @param t throwable
     * @return безопасное, ограниченное по длине сообщение
     */
    private String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return (m.length() > 200) ? m.substring(0, 200) : m;
    }
}
