package com.groviate.telegramcodereviewbot.exception;

public class GitlabClientException extends RuntimeException {
    public GitlabClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
