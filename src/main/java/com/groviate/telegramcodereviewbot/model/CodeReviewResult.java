package com.groviate.telegramcodereviewbot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO для публикации результата ревью AI ботом
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeReviewResult {

    private Integer score; //Шкала оценки

    private String summary; //Краткое описание ревью

    private List<CodeSuggestion> suggestions; //Список все найденных проблем и предложений

    @Builder.Default
    private LocalDateTime analyzedAt = LocalDateTime.now(); //Дата и время выполнения ревью для отслеживания истории

    private String metadata; //Статистика анализа (N количество файлов / N строк)

}
