package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Ошибка постановки/обработки ревью (очередь переполнена и т.п.).
 */
public class ReviewProcessingException extends CodeReviewBotException {
    public ReviewProcessingException(String message, HttpStatus status, Throwable cause) {
        super("REVIEW_PROCESSING", status, message, cause);
    }
}