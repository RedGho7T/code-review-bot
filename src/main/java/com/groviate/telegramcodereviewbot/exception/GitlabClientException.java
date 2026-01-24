package com.groviate.telegramcodereviewbot.exception;

import org.springframework.http.HttpStatus;

/**
 * Ошибка при обращении к GitLab API / публикации комментариев / чтении MR.
 */
public class GitlabClientException extends CodeReviewBotException {

    public GitlabClientException(String message, Throwable cause) {
        super("GITLAB_ERROR", HttpStatus.BAD_GATEWAY, message, cause);
    }

    public GitlabClientException(String message) {
        super("GITLAB_ERROR", HttpStatus.BAD_GATEWAY, message);
    }
}