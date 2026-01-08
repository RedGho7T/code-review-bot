package com.groviate.telegramcodereviewbot.controller;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 *Создан для проверки запросов к AI, например: localhost:8080/api/v1/review/24/288
 *Сейчас запрос проходит, но ошибка 429 по квоте (нет дс на балансе)
 */
@RestController
@RequestMapping("/api/v1/review")
public class ReviewController {

    private final GitLabMergeRequestClient gitlab;
    private final CodeReviewService review;

    public ReviewController(GitLabMergeRequestClient gitlab, CodeReviewService review) {
        this.gitlab = gitlab;
        this.review = review;
    }

    @GetMapping("/{projectId}/{mrIid}")
    public CodeReviewResult run(@PathVariable int projectId, @PathVariable int mrIid) {
        MergeRequest mr = gitlab.getMergeRequest(projectId, mrIid);
        List<MergeRequestDiff> diffs = gitlab.getChanges(projectId, mrIid);
        return review.analyzeCode(mr, diffs);
    }
}