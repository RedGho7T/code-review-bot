package com.groviate.telegramcodereviewbot.integration.controller;

import com.groviate.telegramcodereviewbot.service.ReviewOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.RejectedExecutionException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "gitlab.webhook.secret=secret")
class MergeRequestWebhookControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReviewOrchestrator orchestrator;

    @Test
    @DisplayName("Если токен некорректный -> 403 Forbidden")
    void givenInvalidToken_whenMergeRequestWebhook_thenReturns403Forbidden() throws Exception {
        mockMvc.perform(post("/api/webhook/gitlab/merge-request")
                        .header("X-Gitlab-Token", "wrong")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload("open")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Если событие не Merge Request -> 200 OK и status=ignored")
    void givenNonMergeRequestEventWhenWebhookReceivedThenReturnsIgnoredAndDoesNotEnqueue() throws Exception {
        mockMvc.perform(post("/api/webhook/gitlab/merge-request")
                        .header("X-Gitlab-Token", "secret")
                        .header("X-Gitlab-Event", "Push Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload("open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Если action неподдерживаемый -> 200 OK и status=ignored")
    void givenUnsupportedActionWhenMergeRequestWebhookThenReturnsIgnoredAndDoesNotEnqueue() throws Exception {
        mockMvc.perform(post("/api/webhook/gitlab/merge-request")
                        .header("X-Gitlab-Token", "secret")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload("close")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                .andExpect(jsonPath("$.action").value("close"));

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("Если action=open и запрос валидный -> 200 OK и status=queued")
    void givenValidOpenActionWhenMergeRequestWebhookThenQueuesReviewAndEnqueues() throws Exception {
        mockMvc.perform(post("/api/webhook/gitlab/merge-request")
                        .header("X-Gitlab-Token", "secret")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload("open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.projectId").value(24))
                .andExpect(jsonPath("$.mrIid").value(290))
                .andExpect(jsonPath("$.action").value("open"));

        verify(orchestrator).enqueueReview(24, 290);
    }

    @Test
    @DisplayName("Если очередь ревью переполнена -> 503 Service Unavailable и error=REVIEW_PROCESSING")
    void givenQueueIsFullWhenEnqueueRejectedThenReturns503WithReviewProcessingError() throws Exception {
        doThrow(new RejectedExecutionException("full"))
                .when(orchestrator).enqueueReview(24, 290);

        mockMvc.perform(post("/api/webhook/gitlab/merge-request")
                        .header("X-Gitlab-Token", "secret")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload("open")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("REVIEW_PROCESSING"))
                .andExpect(jsonPath("$.message").value("Queue is full"))
                .andExpect(jsonPath("$.path").value("/api/webhook/gitlab/merge-request"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private static String validPayload(String action) {
        return """
                {
                  "object_attributes": {
                    "action": "%s",
                    "target_project_id": 24,
                    "iid": 290
                  }
                }
                """.formatted(action);
    }
}