package com.groviate.telegramcodereviewbot.dto;

public record ReviewFinishedEvent(
        String runId,
        Integer projectId,
        Integer mrIid,
        String mrTitle,
        String mrUrl,
        int score,
        int filesChanged,
        boolean success
) {}