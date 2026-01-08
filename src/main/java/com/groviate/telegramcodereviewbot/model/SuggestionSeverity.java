package com.groviate.telegramcodereviewbot.model;

import lombok.Getter;

/**
 * Приоритет необходимых исправлений / доработок
 */
@Getter
public enum SuggestionSeverity {

    CRITICAL("Критическое - обязательно исправить"),

    WARNING("Предупреждение - желательно исправить"),

    INFO("Информация - можно улучшить");

    private final String description;

    SuggestionSeverity(String description) {
        this.description = description;
    }

}
