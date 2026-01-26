package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.CodeSuggestion;
import com.groviate.telegramcodereviewbot.model.ReviewCategory;
import com.groviate.telegramcodereviewbot.model.SuggestionSeverity;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис для форматирования результатов в markdown для публикации в Gitlab
 * <p>
 * * 1. Берет структурированный CodeReviewResult (JSON)
 * * 2. Преобразует его в красивый markdown текст
 * * 3. Добавляет эмодзи, заголовки, таблицы для лучшей читаемости
 * * 4. Возвращает готовый текст для публикации в GitLab MR
 */
@Service
@Slf4j
@NoArgsConstructor
public class CommentFormatterService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /**
     * Метод для преобразования результата ревью в markdown
     * <p>
     * 1. Начинаем с заголовка и оценки (score/10)
     * 2. Добавляем описание (summary)
     * 3. Группируем предложения (suggestions) по категориям
     * 4. Для каждой категории создаём секцию с эмодзи и пунктами
     * 5. В конце добавляем метаинформацию (время анализа, источники)
     *
     * @param result - объект CodeReviewResult из CodeReviewService
     * @return готовый Markdown текст для публикации в GitLab
     */
    public String formatReview(CodeReviewResult result) {

        log.debug("Начинаем форматирование результата ревью с оценкой: {}", result.getScore());

        StringBuilder markdown = new StringBuilder();

        markdown.append(formatScoreHeader(result.getScore())); //добавляем заголовок с оценкой

        if (result.getSummary() != null && !result.getSummary().isEmpty()) {
            markdown.append("\n").append(result.getSummary()).append("\n"); //добавляем описание ревью
        }
        if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
            markdown.append("\n").append(formatSuggestions(result.getSuggestions()));
        }
        markdown.append("\n").append(formatMetadata(result));

        String formattedMarkdown = markdown.toString();
        log.debug("Результат ревью успешно отформатирован в markdown, размер: {}", formattedMarkdown.length());

        return formattedMarkdown;
    }

    /**
     * Группирует предложения по категориям и форматирует их
     * <p>
     * 1. Берём список всех suggestions
     * 2. Группируем их по category с помощью Collectors.groupingBy()
     * 3. Для каждой группы создаём отдельную секцию
     * 4. В секции перечисляем все suggestions этой категории
     *
     * @param suggestions - список CodeSuggestion объектов
     * @return отформатированная строка с разделами по категориям
     */
    private String formatSuggestions(List<CodeSuggestion> suggestions) {
        // для каждой категории свой список suggestions
        Map<ReviewCategory, List<CodeSuggestion>> groupedByCategory = suggestions.stream()
                .filter(s -> s.getCategory() != null)
                // группируем по названию категории
                .collect(Collectors.groupingBy(CodeSuggestion::getCategory));

        // cтроим Markdown секцию для каждой категории
        StringBuilder markdown = new StringBuilder();

        // проходимся по каждой категории
        groupedByCategory.forEach((category, categoryIssues) -> {
            String categoryEmoji = getCategoryEmoji(category);

            markdown.append("\n### ")
                    .append(categoryEmoji)
                    .append(" ")
                    .append(category.getDescription())
                    .append("\n");

            categoryIssues.forEach(suggestion -> {
                // Эмодзи для severity (серьёзность проблемы)
                String severityEmoji = getSeverityEmoji(suggestion.getSeverity());

                markdown.append("- ")
                        .append(severityEmoji)
                        .append(" ")
                        .append(suggestion.getMessage())
                        .append("\n");
            });
        });
        return markdown.toString();
    }

    /**
     * Форматирует метаинформацию внизу комментария
     *
     * @param result - объект CodeReviewResult с метаинформацией
     * @return отформатированная строка с подписью и метаданными
     */
    private String formatMetadata(CodeReviewResult result) {
        StringBuilder metadata = new StringBuilder();

        metadata.append("---\n");
        metadata.append("*🤖 Автоматический AI code review*\n\n");

        if (result.getAnalyzedAt() != null) {
            String formattedTime = result.getAnalyzedAt().format(DATE_FORMATTER);
            metadata.append("*Анализ выполнен: ").append(formattedTime).append("*\n");
        }

        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            metadata.append("\n*Статистика:* ").append(result.getMetadata()).append("\n");
        }

        return metadata.toString();
    }

    /**
     * Дополнительный метод для форматирования заголовка с оценкой и эмодзи
     *
     * @param score - оценка от 1 до 10
     * @return отформатированная строка типа "🌟 Результат ревью: 9/10"
     */
    private String formatScoreHeader(Integer score) {
        int safeScore = (score == null) ? 0 : score;

        String emoji;
        if (safeScore >= 9) {
            emoji = "🌟";
        } else if (safeScore >= 7) {
            emoji = "✅";
        } else if (safeScore >= 5) {
            emoji = "⚠️";
        } else {
            emoji = "❌";
        }

        return String.format("## %s Результат ревью: %d/10%n", emoji, safeScore);
    }

    /**
     * Возвращает эмодзи для категории
     *
     * @param category - название категории (строка)
     * @return эмодзи для этой категории
     */
    private String getCategoryEmoji(ReviewCategory category) {
        if (category == null) return "ℹ️";
        return switch (category) {
            case NAMING_CONVENTION -> "🏷️";
            case PERFORMANCE -> "🚀";
            case SECURITY -> "🔒";
            case DESIGN_PATTERN -> "🏗️";
            case ERROR_HANDLING -> "🧯";
            case CODE_STYLE -> "🎨";
            case OTHER -> "ℹ️";
        };
    }

    /**
     * Возвращает эмодзи для серьёзности проблемы
     *
     * @param severity - название серьёзности (строка)
     * @return эмодзи для этой серьёзности
     */
    private String getSeverityEmoji(SuggestionSeverity severity) {
        if (severity == null) return "⚪";
        return switch (severity) {
            case CRITICAL -> "🔴";
            case WARNING -> "🟡";
            case INFO -> "🔵";
        };
    }
}