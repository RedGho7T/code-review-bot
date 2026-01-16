package com.groviate.telegramcodereviewbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Управление и хранение настроек код-ревью бота.
 * Свойства загружаются из конфигурационного файла application.yml с префиксом "code-review".
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

    //для включения / отключения RAG
    private boolean ragEnabled = true;

    //настройка количества файлов для анализа за раз, пока на уточнении лимитов AI
    private Integer maxFilesPerReview = 20;

    //максимум строк в одном файле для анализа, также на уточнении лимитов AI
    private Integer maxLinesPerFile = 500;

    // лимит символов diff на один файл
    private Integer maxDiffCharsPerFile = 12000;

    // лимит символов всех diffs суммарно
    private Integer maxDiffCharsTotal = 60000;

    // общий лимит символов всего промпта (страховка)
    private Integer maxPromptCharsTotal = 85000;

    // включение/выключение inline-комментариев в Гитлаб (к конкретным строкам с изменениями)
    private boolean inlineEnabled = true;

    // максимум inline сообщений
    private Integer maxInlineComments = 10;

    // чтобы не заспамить один файл
    private Integer maxInlineCommentsPerFile = 3;

    // лимит символов одного inline сообщения
    private Integer maxInlineCommentChars = 1200;

    private Integer inlinePublishDelayMs = 200;

    // включать/выключать polling scheduler
    private boolean schedulerEnabled = false;

    // cron для scheduler (по умолчанию раз в 15 минут)
    private String schedulerCron = "0 */15 * * * *";

    // “окно свежести”: проверять только MR, обновлённые за последние N минут
    private Integer schedulerLookBackMinutes = 30;

    private Integer schedulerPerProjectLimit = 10;

    private boolean statusCommentsEnabled = true;
}
