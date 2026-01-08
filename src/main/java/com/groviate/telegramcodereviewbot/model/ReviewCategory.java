package com.groviate.telegramcodereviewbot.model;

/**
 * Категории проблем, которые может найти AI бот в коде
 */
public enum ReviewCategory {

    NAMING_CONVENTION("Нарушение соглашения об именовании"),

    PERFORMANCE("Проблемы производительности"),

    SECURITY("Уязвимость безопасности"),

    DESIGN_PATTERN("Проблема с дизайном и архитектурой"),

    ERROR_HANDLING("Неправильная обработка ошибок"),

    CODE_STYLE("Нарушение стиля кода"),

    OTHER("Другое");

    private final String description;

    ReviewCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

}
