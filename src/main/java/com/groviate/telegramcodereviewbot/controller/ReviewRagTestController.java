package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.InlineCommentPlannerService;
import com.groviate.telegramcodereviewbot.service.ReviewOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/test/review")
public class ReviewRagTestController {

    private final CodeReviewService codeReviewService;
    private final GitLabMergeRequestClient gitLabMergeRequestClient;
    private final InlineCommentPlannerService inlineCommentPlannerService;
    private final CodeReviewProperties codeReviewProperties;
    private final ReviewOrchestrator reviewOrchestrator;

    public ReviewRagTestController(CodeReviewService codeReviewService,
                                   GitLabMergeRequestClient gitLabMergeRequestClient,
                                   InlineCommentPlannerService inlineCommentPlannerService,
                                   CodeReviewProperties codeReviewProperties,
                                   ReviewOrchestrator reviewOrchestrator) {
        this.codeReviewService = codeReviewService;
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
        this.inlineCommentPlannerService = inlineCommentPlannerService;
        this.codeReviewProperties = codeReviewProperties;
        this.reviewOrchestrator = reviewOrchestrator;
    }

    private static final String KEY_PLANNED_INLINE = "planned_inline";
    private static final String KEY_PUBLISHED = "published";
    private static final String KEY_FAILED = "failed";

    /**
     * Запускает реальный сценарий бота (оркестратор сам опубликует итог + inline).
     */
    @PostMapping("/orchestrator/run/{projectId}/{mrId}")
    public Map<String, Object> runOrchestrator(@PathVariable Integer projectId,
                                               @PathVariable Integer mrId) {
        reviewOrchestrator.enqueueReview(projectId, mrId);
        return Map.of("status", "queued", "projectId", projectId, "mrId", mrId);
    }

    /**
     * Диагностика: AI анализ + планирование inline, без публикации.
     */
    @PostMapping("/inline/analyze-plan/{projectId}/{mrId}")
    public Map<String, Object> analyzeAndPlanInline(@PathVariable Integer projectId,
                                                    @PathVariable Integer mrId) {
        var mr = gitLabMergeRequestClient.getMergeRequest(projectId, mrId);
        var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);

        CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

        var planned = inlineCommentPlannerService.plan(result, diffs, codeReviewProperties);

        var items = planned.stream()
                .map(c -> Map.of(
                        "newPath", c.newPath(),
                        "newLine", c.newLine(),
                        "oldPath", c.oldPath(),
                        "bodyPreview", preview(c.body())
                ))
                .toList();

        return Map.of(
                "score", result.getScore(),
                "summary", result.getSummary(),
                "suggestions_total", result.getSuggestions() == null ? 0 : result.getSuggestions().size(),
                "diff_files", diffs == null ? 0 : diffs.size(),
                KEY_PLANNED_INLINE, planned.size(),
                "planned_items", items
        );
    }

    /**
     * Публикует inline на основе готового CodeReviewResult (ручная проверка постинга).
     */
    @PostMapping("/inline/publish/{projectId}/{mrId}")
    public Map<String, Object> publishInlineFromResult(@PathVariable Integer projectId,
                                                       @PathVariable Integer mrId,
                                                       @RequestBody CodeReviewResult result) {
        var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);

        var planned = inlineCommentPlannerService.plan(result, diffs, codeReviewProperties);
        if (planned == null || planned.isEmpty()) {
            return Map.of(KEY_PLANNED_INLINE,
                    +0, KEY_PUBLISHED,
                    +0, KEY_FAILED, 0, "message",
                    "No inline comments planned");
        }

        // dry-run support
        if (codeReviewProperties.isDryRun()) {
            return Map.of(
                    KEY_PLANNED_INLINE, planned.size(),
                    KEY_PUBLISHED, 0,
                    KEY_FAILED, 0,
                    "message", "DRY-RUN enabled: nothing published"
            );
        }

        var refs = gitLabMergeRequestClient.getLatestDiffRefs(projectId, mrId);

        int ok = 0;
        int failed = 0;

        for (var c : planned) {
            try {
                var position = new GitLabMergeRequestClient.LinePosition(
                        c.oldPath(),
                        c.newPath(),
                        c.oldLine(),
                        c.newLine()
                );

                gitLabMergeRequestClient.postLineComment(
                        projectId,
                        mrId,
                        refs,
                        position,
                        c.body()
                );
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to publish inline comment to {}:{} ({})", c.newPath(), c.newLine(), e.getMessage());
            }
        }

        return Map.of(KEY_PLANNED_INLINE, planned.size(), KEY_PUBLISHED, ok, KEY_FAILED, failed);
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }
}
