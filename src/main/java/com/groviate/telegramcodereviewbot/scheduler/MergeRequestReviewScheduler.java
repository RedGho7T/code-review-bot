package com.groviate.telegramcodereviewbot.scheduler;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.service.ReviewOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class MergeRequestReviewScheduler {

    private final CodeReviewProperties props;
    private final GitLabMergeRequestClient mrClient;
    private final ReviewOrchestrator orchestrator;

    public MergeRequestReviewScheduler(CodeReviewProperties props,
                                       GitLabMergeRequestClient mrClient,
                                       ReviewOrchestrator orchestrator) {
        this.props = props;
        this.mrClient = mrClient;
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${code-review.scheduler-cron:0 */10 * * * *}")
    public void tick() {
        if (!props.isEnabled() || !props.isSchedulerEnabled()) return;

        Integer[] projectIds = props.getProjectIds();
        if (projectIds == null || projectIds.length == 0) return;

        int lookBackMin = props.getSchedulerLookBackMinutes() == null ? 30 : props.getSchedulerLookBackMinutes();
        int perProject = props.getSchedulerPerProjectLimit() == null ? 10 : props.getSchedulerPerProjectLimit();

        Instant updatedAfter = Instant.now().minus(Duration.ofMinutes(lookBackMin));

        for (Integer projectId : projectIds) {
            try {
                var mrs = mrClient.getOpenMergeRequestsUpdatedAfter(projectId, updatedAfter, perProject);
                for (var mr : mrs) {
                    if (mr == null || mr.getIid() == null) continue;
                    orchestrator.enqueueReview(projectId, mr.getIid());
                }
            } catch (Exception e) {
                log.warn("Scheduler failed for project {}: {}", projectId, e.getMessage());
            }
        }
    }
}