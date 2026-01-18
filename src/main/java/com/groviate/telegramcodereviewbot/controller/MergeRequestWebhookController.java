package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.service.ReviewOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Получает события от GitLab (создание/обновление MR) и запускает анализ через ReviewOrchestrator
 */
@RestController
@Slf4j
@RequestMapping("/api/webhook/gitlab")
public class MergeRequestWebhookController {

    private static final String ACTION_KEY = "action";
    private static final String STATUS_KEY = "status";
    private static final String STATUS_IGNORED = "ignored";

    private final ReviewOrchestrator orchestrator;
    private final String secret;

    public MergeRequestWebhookController(ReviewOrchestrator orchestrator,
                                         @Value("${gitlab.webhook.secret:}") String secret) {
        this.orchestrator = orchestrator;
        this.secret = secret;
    }

    @PostMapping("/merge-request")
    public ResponseEntity<Map<String, Object>> onMergeRequest(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody Map<String, Object> payload
    ) {
        if (secret != null && !secret.isBlank() && (!secret.equals(token))) {
            log.warn("Invalid webhook token");
            return ResponseEntity.status(403).body(Map.of(STATUS_KEY, "forbidden"));
        }

        if (event == null || !event.toLowerCase().contains("merge request")) {
            return ResponseEntity.ok(Map.of(STATUS_KEY, STATUS_IGNORED, "reason", "not MR event"));
        }

        Map<String, Object> attrs = safeMap(payload.get("object_attributes"));
        String action = String.valueOf(attrs.getOrDefault(ACTION_KEY, ""));

        // берем projectId
        Integer projectId = intOrNull(attrs.get("target_project_id"));
        if (projectId == null) {
            projectId = intOrNull(attrs.get("source_project_id"));
        }
        if (projectId == null) {
            Map<String, Object> project = safeMap(payload.get("project"));
            projectId = intOrNull(project.get("id"));
        }

        Integer mrIid = intOrNull(attrs.get("iid"));

        if (projectId == null || mrIid == null) {
            return ResponseEntity.ok(Map.of(STATUS_KEY, STATUS_IGNORED, "reason", "no projectId or iid"));
        }

        // реагируем только на действия, где есть изменения в MR (open, reopen, update)
        if (!("open".equalsIgnoreCase(action)
                || "reopen".equalsIgnoreCase(action)
                || "update".equalsIgnoreCase(action))) {
            return ResponseEntity.ok(Map.of(STATUS_KEY, STATUS_IGNORED, ACTION_KEY, action));
        }

        try {
            orchestrator.enqueueReview(projectId, mrIid);
        } catch (RejectedExecutionException e) {
            return ResponseEntity.status(503)
                    .body(Map.of(STATUS_KEY, "queue_full", "error", e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                STATUS_KEY, "queued",
                "projectId", projectId,
                "mrIid", mrIid,
                ACTION_KEY, action
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
