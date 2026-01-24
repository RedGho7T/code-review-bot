package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Ошибка OpenAI, которую не имеет смысла ретраить.
 * Примеры: 400 (плохой запрос), 401/403 (ключ/права), NonTransientAiException.
 */
public class AiNonRetryableException extends CodeReviewBotException {

    public AiNonRetryableException(String message, Throwable cause) {
        super("OPENAI_NON_RETRYABLE", HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    public AiNonRetryableException(String message) {
        super("OPENAI_NON_RETRYABLE", HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}