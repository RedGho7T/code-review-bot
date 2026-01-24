package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Невалидный webhook (токен/структура payload).
 */
public class WebhookValidationException extends CodeReviewBotException {
    public WebhookValidationException(String message, HttpStatus status) {
        super("WEBHOOK_VALIDATION", status, message);
    }
}
