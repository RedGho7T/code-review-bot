package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.GitLabCommentService;
import com.groviate.telegramcodereviewbot.service.PromptTemplateService;
import com.groviate.telegramcodereviewbot.service.RagContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMethod;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Контроллер для ТЕСТИРОВАНИЯ Фазы 4: Публикация результатов ревью c RAG
 */
@RestController
@Slf4j
@RequestMapping("/api/test/review")
public class ReviewRagTestController {

    private final GitLabCommentService gitLabCommentService;
    private final CodeReviewService codeReviewService;
    private final GitLabMergeRequestClient gitLabMergeRequestClient;
    private final RagContextService ragContextService;
    private final PromptTemplateService promptTemplateService;

    public ReviewRagTestController(GitLabCommentService gitLabCommentService,
                                   CodeReviewService codeReviewService,
                                   GitLabMergeRequestClient gitLabMergeRequestClient,
                                   RagContextService ragContextService,
                                   PromptTemplateService promptTemplateService) {
        this.gitLabCommentService = gitLabCommentService;
        this.codeReviewService = codeReviewService;
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
        this.ragContextService = ragContextService;
        this.promptTemplateService = promptTemplateService;
    }

    @GetMapping("/rag/{projectId}/{mrId}")
    public Map<String, Object> debugRag(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId
    ) {
        var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);

        String mergedDiffs = diffs.stream()
                .filter(d -> !d.isDeletedFile())
                .map(d -> d.getDiff() == null ? "" : d.getDiff())
                .filter(s -> !s.isBlank())
                .reduce("", (a, b) -> a + "\n" + b);

        String ragContext = ragContextService.getContextForCode(mergedDiffs);

        String preview = ragContext.length() > 1500
                ? ragContext.substring(0, 1500) + "..."
                : ragContext;

        return Map.of(
                "rag_chars", ragContext.length(),
                "rag_preview", preview
        );
    }

    @GetMapping("/prompt/{projectId}/{mrId}")
    public Map<String, Object> debugPrompt(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId
    ) {
        var mr = gitLabMergeRequestClient.getMergeRequest(projectId, mrId);
        var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);

        // берём RAG (можно так же как в /rag)
        String ragContext = ragContextService.getContextForCode(
                diffs.stream()
                        .map(d -> d.getDiff() == null ? "" : d.getDiff())
                        .reduce("", (a, b) -> a + "\n" + b)
        );

        String prompt = promptTemplateService.preparePrompt(
                diffs,
                mr.getTitle(),
                mr.getDescription() == null ? "" : mr.getDescription(),
                ragContext
        );

        String preview = prompt.length() > 2000 ? prompt.substring(0, 2000) + "..." : prompt;

        return Map.of(
                "prompt_chars", prompt.length(),
                "rag_chars", ragContext == null ? 0 : ragContext.length(),
                "prompt_preview", preview,
                "placeholder_note",
                "Убедись, что в user-prompt.txt есть {rag_context} и {diffs} (или {code_changes})"
        );
    }

    @RequestMapping(
            value = "/status/{projectId}/{mrId}",
            method = {RequestMethod.GET, RequestMethod.POST}
    )
    public Map<String, Object> publishStatus(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId,
            @RequestParam(required = false) String message
    ) {
        String msg = (message == null || message.isBlank())
                ? "🔄 Статус: тестовое сообщение (" + LocalDateTime.now() + ")"
                : message;

        gitLabCommentService.publishStatusComment(projectId, mrId, msg);

        return Map.of(
                "status", "ok",
                "published", !msg.isBlank(),
                "message", msg
        );
    }

    @PostMapping("/analyze-with-status/{projectId}/{mrId}")
    public Map<String, Object> analyzeWithStatus(
            @PathVariable Integer projectId,
            @PathVariable Integer mrId
    ) {
        String runId = "run-" + System.currentTimeMillis();

        try {
            gitLabCommentService.publishStatusComment(projectId, mrId,
                    "[" + runId + "] Старт анализа MR. Собираю изменения…");

            var mr = gitLabMergeRequestClient.getMergeRequest(projectId, mrId);
            var diffs = gitLabMergeRequestClient.getChanges(projectId, mrId);

            gitLabCommentService.publishStatusComment(projectId, mrId,
                    "[" + runId + "] Файлов к анализу: " + (diffs == null ? 0 : diffs.size())
                            + ". Запускаю AI…");

            CodeReviewResult result = codeReviewService.analyzeCode(mr, diffs);

            gitLabCommentService.publishStatusComment(projectId, mrId,
                    "[" + runId + "] AI завершил анализ (score=" + result.getScore()
                            + "). Публикую ревью…");

            gitLabCommentService.publishReview(projectId, mrId, result);

            gitLabCommentService.publishStatusComment(projectId, mrId,
                    "[" + runId + "] Готово. Ревью опубликовано. Score=" + result.getScore() + "/10");

            return Map.of(
                    "status", "success",
                    "run_id", runId,
                    "score", result.getScore(),
                    "summary", result.getSummary()
            );

        } catch (Exception e) {
            // статус об ошибке тоже полезно публиковать
            gitLabCommentService.publishStatusComment(projectId, mrId,
                    "[" + runId + "] Ошибка анализа: " + e.getClass().getSimpleName() + " - " + e.getMessage());

            return Map.of(
                    "status", "error",
                    "run_id", runId,
                    "error", e.getMessage()
            );
        }
    }
}
