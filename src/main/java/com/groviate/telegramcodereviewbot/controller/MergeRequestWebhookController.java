package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.exception.ReviewProcessingException;
import com.groviate.telegramcodereviewbot.service.ReviewOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import com.groviate.telegramcodereviewbot.exception.WebhookValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Получает POST события на {@code /api/webhook/gitlab/merge-request} от GitLab о создании,
 * повторно открытии или обновлении Merge Request'ов и ставит их в очередь на code review.
 */
@RestController
@Slf4j
@RequestMapping("/api/webhook/gitlab")
public class MergeRequestWebhookController {

    private static final String ACTION_KEY = "action";
    private static final String STATUS_KEY = "status";
    private static final String STATUS_IGNORED = "ignored";
    private static final String HEADER_TOKEN = "X-Gitlab-Token";
    private static final String HEADER_EVENT = "X-Gitlab-Event";

    private final ReviewOrchestrator orchestrator;
    private final String secret;
    private final Environment environment;

    /**
     * @param orchestrator - ReviewOrchestrator для постановки MR в очередь
     * @param secret       - webhook secret из конфигурации gitlab.webhook.secret (опционально)
     */
    public MergeRequestWebhookController(ReviewOrchestrator orchestrator,
                                         @Value("${gitlab.webhook.secret:}") String secret,
                                         Environment environment) {
        this.orchestrator = orchestrator;
        this.secret = secret;
        this.environment = environment;
    }

    /**
     * POST endpoint для получения webhook событий от GitLab
     * <p>
     * URL: {@code POST /api/webhook/gitlab/merge-request}
     * <p>
     * Проверки и фильтрация:
     * <ol>
     *   <li>Если secret установлен → проверяет X-Gitlab-Token header (403 если не совпадает)</li>
     *   <li>Проверяет X-Gitlab-Event header содержит "merge request" (игнорирует другие события)</li>
     *   <li>Извлекает из JSON payload:
     *       <ul>
     *           <li>projectId: из object_attributes.target_project_id → source_project_id → project.id</li>
     *           <li>mrIid: из object_attributes.iid</li>
     *           <li>action: из object_attributes.action</li>
     *       </ul>
     *   </li>
     *   <li>Фильтрует по действиям: только "open", "reopen", "update" (игнорирует close, merge и т.д.)</li>
     *   <li>Ставит в очередь через orchestrator.enqueueReview(projectId, mrIid)</li>
     * </ol>
     *
     * @param token   - значение header {@code X-Gitlab-Token} (проверяется если secret установлен)
     * @param event   - значение header {@code X-Gitlab-Event} (должно содержать "merge request")
     * @param payload - JSON body от GitLab webhook с данными о MR
     * @return ResponseEntity с JSON
     */
    @PostMapping("/merge-request")
    public ResponseEntity<Map<String, Object>> onMergeRequest(
            @RequestHeader(value = HEADER_TOKEN, required = false) String token,
            @RequestHeader(value = HEADER_EVENT, required = false) String event,
            @RequestBody Map<String, Object> payload
    ) {
        validateSecretToken(token);

        if (event == null || !event.toLowerCase(Locale.ROOT).contains("merge request")) {
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
            throw new WebhookValidationException("Invalid webhook payload: no projectId or iid", HttpStatus.BAD_REQUEST);
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
            throw new ReviewProcessingException("Queue is full", HttpStatus.SERVICE_UNAVAILABLE, e);
        }

        return ResponseEntity.ok(Map.of(
                STATUS_KEY, "queued",
                "projectId", projectId,
                "mrIid", mrIid,
                ACTION_KEY, action
        ));
    }

    /**
     * Безопасное приведение Object к Map
     *
     * @param o - объект для приведения
     * @return Map если o это Map, иначе пустой Map.of()
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    /**
     * Преобразует Object в Integer безопасно
     *
     * @param o - объект для преобразования (может быть null, Number или String)
     * @return Integer значение или null если невозможно преобразовать
     */
    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Валидирует секретный токен вебхука GitLab
     *
     * @param tokenFromHeader значение header {@code X-Gitlab-Token} (может быть null)
     * @throws WebhookValidationException если secret обязателен, но не задан, либо токен отсутствует/не совпадает
     */
    private void validateSecretToken(String tokenFromHeader) {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));

        if (secret == null || secret.isBlank()) {
            if (isProd) {
                log.warn("Webhook secret is not configured in prod profile");
                throw new WebhookValidationException("Webhook secret is required in prod", HttpStatus.FORBIDDEN);
            }
            return;
        }

        // secret задан -> token обязателен
        if (tokenFromHeader == null || tokenFromHeader.isBlank()) {
            log.warn("Missing webhook token header: {}", HEADER_TOKEN);
            throw new WebhookValidationException("Missing webhook token", HttpStatus.FORBIDDEN);
        }
        boolean equals = MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                tokenFromHeader.getBytes(StandardCharsets.UTF_8)
        );
        if (!equals) {
            log.warn("Invalid webhook token");
            throw new WebhookValidationException("Invalid webhook token", HttpStatus.FORBIDDEN);
        }
    }
}
