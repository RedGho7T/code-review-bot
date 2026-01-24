package com.groviate.telegramcodereviewbot.unit.service;

import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.CodeSuggestion;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.model.SuggestionSeverity;
import com.groviate.telegramcodereviewbot.service.InlineCommentPlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InlineCommentPlannerService: планирование inline-комментариев")
class InlineCommentPlannerServiceUnitTest {

    private final InlineCommentPlannerService planner = new InlineCommentPlannerService();

    @Test
    @DisplayName("Добавленная строка: oldLine = null, newLine присутствует")
    void givenSuggestionOnAddedLineWhenPlanThenOldLineIsNullNewLinePresent() {
        String diffText = """
                @@ -1,3 +1,3 @@
                 line1
                -line2
                +line2 changed
                 line3
                """;

        MergeRequestDiff diff = MergeRequestDiff.builder()
                .oldPath("README.md")
                .newPath("README.md")
                .diff(diffText)
                .build();

        CodeReviewResult result = CodeReviewResult.builder()
                .score(6)
                .summary("s")
                .suggestions(List.of(
                        CodeSuggestion.builder()
                                .fileName("README.md")
                                .lineNumber(2)
                                .severity(SuggestionSeverity.WARNING)
                                .message("msg")
                                .build()
                ))
                .build();

        CodeReviewProperties props = new CodeReviewProperties();
        props.setMaxInlineComments(10);
        props.setMaxInlineCommentsPerFile(10);

        List<InlineCommentPlannerService.InlineComment> planned = planner.plan(result, List.of(diff), props);

        assertThat(planned).hasSize(1);
        InlineCommentPlannerService.InlineComment c = planned.getFirst();

        assertThat(c.newPath()).isEqualTo("README.md");
        assertThat(c.newLine()).isEqualTo(2);
        assertThat(c.oldLine()).isNull();
    }

    @Test
    @DisplayName("Контекстная строка: oldLine совпадает с newLine")
    void givenSuggestionOnContextLineWhenPlanThenOldLineEqualsNewLine() {
        String diffText = """
                @@ -1,3 +1,3 @@
                 line1
                -line2
                +line2 changed
                 line3
                """;

        MergeRequestDiff diff = MergeRequestDiff.builder()
                .oldPath("README.md")
                .newPath("README.md")
                .diff(diffText)
                .build();

        CodeReviewResult result = CodeReviewResult.builder()
                .score(6)
                .summary("s")
                .suggestions(List.of(
                        CodeSuggestion.builder()
                                .fileName("README.md")
                                .lineNumber(3) // контекстная строка
                                .severity(SuggestionSeverity.WARNING)
                                .message("msg")
                                .build()
                ))
                .build();

        CodeReviewProperties props = new CodeReviewProperties();
        props.setMaxInlineComments(10);
        props.setMaxInlineCommentsPerFile(10);

        List<InlineCommentPlannerService.InlineComment> planned = planner.plan(result, List.of(diff), props);

        assertThat(planned).hasSize(1);
        InlineCommentPlannerService.InlineComment c = planned.getFirst();

        assertThat(c.newLine()).isEqualTo(3);
        assertThat(c.oldLine()).isEqualTo(3);
    }
}
