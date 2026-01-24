package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Ошибка при обращении к OpenAI / получении ответа от модели.
 */
public class AiReviewException extends CodeReviewBotException {
    public AiReviewException(String message, Throwable cause) {
        super("OPENAI_ERROR", HttpStatus.BAD_GATEWAY, message, cause);
    }

    public AiReviewException(String message) {
        super("OPENAI_ERROR", HttpStatus.BAD_GATEWAY, message);
    }
}