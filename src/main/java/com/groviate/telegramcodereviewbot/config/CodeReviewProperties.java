package com.groviate.telegramcodereviewbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 *Управление и хранение настроек код-ревью бота.
 *Свойства загружаются из конфигурационного файла application.yml с префиксом "code-review".
 */
@Component
@ConfigurationProperties(prefix = "code-review")
@Data
public class CodeReviewProperties {

    //false - для ручного отключения бота в проекте
    private boolean enabled = true;

    //true - для тестирования, не публикует результаты в Gitlab
    private boolean dryRun = false;

    //массив id проектов Habit
    private Integer[] projectIds = {};

    //настройка количества файлов для анализа за раз, пока на уточнении лимитов AI
    private Integer maxFilesPerReview = 20;

    //максимум строк в одном файле для анализа, также на уточнении лимитов AI
    private Integer maxLinesPerFile = 500;
}
