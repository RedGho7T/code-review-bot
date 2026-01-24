package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.exception.CodeReviewBotException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Глобальный обработчик исключений REST-контроллеров.
 * <p>
 * Преобразует доменные исключения бота в единый JSON-ответ и логирует ошибки.
 * Для неизвестных ошибок возвращает стандартный ответ 500.
 */
@RestControllerAdvice
@Slf4j
public class CodeReviewExceptionHandler {

    @ExceptionHandler(CodeReviewBotException.class)
    public ResponseEntity<Map<String, Object>> handleBotException(CodeReviewBotException ex,
                                                                  HttpServletRequest request) {
        log.warn("Исключение бота: code={}, status={}, path={}",
                ex.getCode(), ex.getStatus(), request.getRequestURI(), ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("path", request.getRequestURI());
        body.put("error", ex.getCode());
        body.put("message", ex.getMessage());

        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex,
                                                                HttpServletRequest request) {
        log.error("Непредвиденная ошибка, по пути={}", request.getRequestURI(), ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("path", request.getRequestURI());
        body.put("error", "UNEXPECTED");
        body.put("message", "Unexpected server error");

        return ResponseEntity.internalServerError().body(body);
    }
}
