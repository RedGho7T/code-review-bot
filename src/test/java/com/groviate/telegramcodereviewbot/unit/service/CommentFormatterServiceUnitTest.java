package com.groviate.telegramcodereviewbot.unit.service;

import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.CodeSuggestion;
import com.groviate.telegramcodereviewbot.model.ReviewCategory;
import com.groviate.telegramcodereviewbot.model.SuggestionSeverity;
import com.groviate.telegramcodereviewbot.service.CommentFormatterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentFormatterServiceUnitTest {

    private final CommentFormatterService formatter = new CommentFormatterService();

    @Test
    @DisplayName("Дан результат ревью с замечаниями и метаданными -> Markdown с группировкой и служебной информацией")
    void givenReviewWithSuggestionsAndMetadataWhenFormatReviewThenBuildsMarkdownWithGroupsAndMetadata() {
        CodeReviewResult result = CodeReviewResult.builder()
                .score(8)
                .summary("Хороший код с несколькими замечаниями")
                .analyzedAt(LocalDateTime.of(2026, 1, 23, 16, 38, 37))
                .metadata("files=16, added=550, removed=9")
                .suggestions(List.of(
                        CodeSuggestion.builder()
                                .category(ReviewCategory.SECURITY)
                                .severity(SuggestionSeverity.CRITICAL)
                                .message("API ключи не должны храниться в коде")
                                .build(),
                        CodeSuggestion.builder()
                                .category(ReviewCategory.CODE_STYLE)
                                .severity(SuggestionSeverity.WARNING)
                                .message("Отсутствует пробел после JavaDoc")
                                .build()
                ))
                .build();

        String md = formatter.formatReview(result);

        assertThat(md)
                .contains("## ✅ Результат ревью: 8/10")
                .contains("Хороший код с несколькими замечаниями")

                // категория + emoji
                .contains("### 🔒 Уязвимость безопасности")
                .contains("- 🔴 API ключи не должны храниться в коде")
                .contains("### 🎨 Нарушение стиля кода")
                .contains("- 🟡 Отсутствует пробел после JavaDoc")

                // мета
                .contains("*Анализ выполнен: 23.01.2026 16:38:37*")
                .contains("*Статистика:* files=16, added=550, removed=9");
    }

    @Test
    @DisplayName("Дано разные оценки ревью при форматировании -> выбирается корректный эмодзи для оценки")
    void givenDifferentScoresWhenFormatReviewThenUsesCorrectScoreEmoji() {
        assertThat(formatter.formatReview(CodeReviewResult.builder()
                .score(9).summary("s")
                .suggestions(List.of())
                .build()))
                .contains("## 🌟 Результат ревью: 9/10");

        assertThat(formatter.formatReview(CodeReviewResult.builder()
                .score(7)
                .summary("s")
                .suggestions(List.of())
                .build()))
                .contains("## ✅ Результат ревью: 7/10");

        assertThat(formatter.formatReview(CodeReviewResult.builder()
                .score(5)
                .summary("s")
                .suggestions(List.of())
                .build()))
                .contains("## ⚠️ Результат ревью: 5/10");

        assertThat(formatter.formatReview(CodeReviewResult.builder()
                .score(3)
                .summary("s")
                .suggestions(List.of())
                .build()))
                .contains("## ❌ Результат ревью: 3/10");
    }
}