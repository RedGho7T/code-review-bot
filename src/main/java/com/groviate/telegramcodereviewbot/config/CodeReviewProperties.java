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

    // Основное управление

    /**
     * Глобально включить/выключить бота
     */
    private boolean enabled = true;

    /**
     * Dry-run: не публиковать результаты в GitLab (ВАЖНО: только тест без отправки комментариев в Gitlab - TRUE)
     */
    private boolean dryRun = false;

    /**
     * Список projectId, где бот активен (пусто = нигде/или зависит от логики использования)
     */
    private Integer[] projectIds = {};

    // RAG (контекст стандартов)

    /**
     * Включение/выключение RAG
     */
    private boolean ragEnabled = true;

    // Лимиты анализа / промпта

    /**
     * Максимум файлов в одном ревью
     */
    private Integer maxFilesPerReview = 20;

    /**
     * Максимум строк в одном файле
     */
    private Integer maxLinesPerFile = 500;

    /**
     * Лимит символов diff на один файл
     */
    private Integer maxDiffCharsPerFile = 12000;

    /**
     * Лимит символов всех diffs суммарно
     */
    private Integer maxDiffCharsTotal = 60000;

    /**
     * Общий лимит символов всего промпта (страховка)
     */
    private Integer maxPromptCharsTotal = 85000;

    // Inline-комментарии (по строкам)

    /**
     * Включение/выключение inline-комментариев
     */
    private boolean inlineEnabled = true;

    /**
     * Максимум inline-комментариев на весь MR
     */
    private Integer maxInlineComments = 10;

    /**
     * Максимум inline-комментариев на один файл
     */
    private Integer maxInlineCommentsPerFile = 3;

    /**
     * Лимит символов одного inline-комментария
     */
    private Integer maxInlineCommentChars = 1200;

    /**
     * Пауза между публикациями inline-комментариев (мс)
     */
    private Integer inlinePublishDelayMs = 200;

    // Scheduler (polling MR)

    /**
     * Включать/выключать polling scheduler
     */
    private boolean schedulerEnabled = false;

    /**
     * Cron для scheduler (по умолчанию раз в 15 минут)
     */
    private String schedulerCron = "0 */15 * * * *";

    /**
     * Окно свежести: проверять только MR, обновлённые за последние N минут
     */
    private Integer schedulerLookBackMinutes = 30;

    /**
     * Лимит MR на проект за один проход
     */
    private Integer schedulerPerProjectLimit = 10;

    // Служебные статус-комментарии

    /**
     * Включение/выключение служебных статус-комментариев
     */
    private boolean statusCommentsEnabled = true;

     /**
     * Включает тестовые REST endpoints (ReviewRagTestController).
     * Должно быть false на проде.
     */
     private boolean testEndpointsEnabled = false;
}
