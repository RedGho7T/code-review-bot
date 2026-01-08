package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Контроллер для тестирования и наглядности.
 * Будет удален на завершении 2-го этапа.
 * Endpoints:
 * localhost:8080/api/merge-requests/all
 * localhost:8080/api/merge-requests/project/24
 * localhost:8080/api/merge-requests/project/24/mr/260
 * localhost:8080/api/merge-requests/grouped
 */
@RestController
@Slf4j
@RequestMapping("/api/merge-requests")
public class MergeRequestController {

    private final GitLabMergeRequestClient gitLabMergeRequestClient;

    private final CodeReviewProperties codeReviewProperties;

    public MergeRequestController(GitLabMergeRequestClient gitLabMergeRequestClient,
                                  CodeReviewProperties codeReviewProperties) {
        this.gitLabMergeRequestClient = gitLabMergeRequestClient;
        this.codeReviewProperties = codeReviewProperties;
    }

    @GetMapping("/all")
    public List<MergeRequest> getAllMergeRequests(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage) {

        List<MergeRequest> allMergeRequests = new ArrayList<>();

        List<Integer> projectIds = List.of(codeReviewProperties.getProjectIds());

        for (Integer projectId : projectIds) {
            try {
                List<MergeRequest> projectMrs = gitLabMergeRequestClient.getMergeRequestsByProjectId(projectId);

                log.info("Проект {}: найдено {} MR", projectId, projectMrs.size());

                projectMrs.forEach(mr -> {
                    mr.setProjectId(projectId);
                    allMergeRequests.add(mr);
                });

            } catch (Exception e) {
                log.warn("Ошибка при получении всех MR из проекта {}: {}", projectId, e.getMessage());
                //Не прерываем процесс получения, потом легче тестить доступы по токену к каждому сервису
            }
        }
        log.info("Всего получено MR: {}", allMergeRequests.size());
        return allMergeRequests;
    }

    @GetMapping("/project/{projectId}")
    public List<MergeRequest> getMergeRequestsByProject(@PathVariable int projectId) {

        List<MergeRequest> mergeRequests = gitLabMergeRequestClient.getMergeRequestsByProjectId(projectId);

        log.info("Получено {} MR из проекта {}", mergeRequests.size(), projectId);

        return mergeRequests;
    }

    @GetMapping("/project/{projectId}/mr/{mergeRequestId}")
    public MergeRequest getMergeRequestDetails(@PathVariable int projectId,
                                               @PathVariable int mergeRequestId) {

        MergeRequest mr = gitLabMergeRequestClient.getMergeRequest(projectId, mergeRequestId);
        mr.setProjectId(projectId);

        log.info("Получены детали MR: {}", mr.getTitle());

        return mr;
    }

    @GetMapping("/grouped")
    public Map<Integer, List<MergeRequest>> getMergeRequestsGroupedByProject() {

        log.info("Получение MR сгруппированных по проектам");

        List<Integer> projectIds = List.of(codeReviewProperties.getProjectIds());

        Map<Integer, List<MergeRequest>> groupedMRs = new LinkedHashMap<>();

        for (Integer projectId : projectIds) {
            try {
                List<MergeRequest> projectMRs = gitLabMergeRequestClient.getMergeRequestsByProjectId(projectId);
                projectMRs.forEach(mr -> mr.setProjectId(projectId));

                groupedMRs.put(projectId, projectMRs);

                log.info("Проект {}: {} MR", projectId, projectMRs.size());

            } catch (Exception e) {
                log.warn("Ошибка при получении MR из проекта {}", projectId);
                groupedMRs.put(projectId, Collections.emptyList());
            }
        }

        return groupedMRs;
    }

}
