package com.groviate.telegramcodereviewbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для предложений / описания проблем, найденных AI ботом при ревью
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeSuggestion {

    private ReviewCategory category; // Категория проблемы

    private SuggestionSeverity severity; //Приоритет исправления

    private String message; //Сообщение об ошибке

    private Integer lineNumber; //Номер строки в файле с ошибкой

    private String fileName; //Пусть к файлу с проблемой

    private String suggestionFix; //Предложения по исправлению
}
