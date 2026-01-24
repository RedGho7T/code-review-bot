package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Ошибка при получении RAG контекста (ChromaDB/embedding).
 */
public class RagContextException extends CodeReviewBotException {
    public RagContextException(String message, Throwable cause) {
        super("RAG_ERROR", HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}