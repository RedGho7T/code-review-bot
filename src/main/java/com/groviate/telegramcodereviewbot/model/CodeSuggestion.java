package com.groviate.telegramcodereviewbot.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeSuggestion {

    private ReviewCategory category; // Категория проблемы

    private SuggestionSeverity severity; //Приоритет исправления

    @JsonAlias({"message", "text"})
    private String message; //Сообщение об ошибке

    private Integer lineNumber; //Номер строки в файле с ошибкой

    @JsonAlias({"fileName", "filePath", "path"})
    private String fileName; //Пусть к файлу с проблемой

    @JsonAlias({"suggestionFix", "suggestedFix"})
    private String suggestionFix; //Предложения по исправлению
}
