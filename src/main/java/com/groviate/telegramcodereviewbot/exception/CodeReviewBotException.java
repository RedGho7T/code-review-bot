package com.groviate.telegramcodereviewbot.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Базовое исключение бота.
 * Хранит "машинный" код ошибки и HTTP-статус, чтобы ExceptionHandler мог
 * централизованно вернуть корректный ответ.
 */
@Getter
public abstract class CodeReviewBotException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected CodeReviewBotException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    protected CodeReviewBotException(String code, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}