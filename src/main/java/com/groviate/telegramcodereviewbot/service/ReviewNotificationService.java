package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.config.TelegramProperties;
import com.groviate.telegramcodereviewbot.dto.ReviewFinishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public class ReviewNotificationService {

    private final TelegramClient telegramClient;
    private final TelegramProperties telegramProperties;
    private static final int TELEGRAM_SAFE_LIMIT = 3500;

    @Async("telegramExecutor")
    @EventListener
    public void onReviewFinished(ReviewFinishedEvent event) {
        String channelId = telegramProperties.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            log.warn("telegram.channel-id пустой -> уведомление не отправляем. runId={}", event.runId());
            return;
        }

        String text = limit(buildMessage(event));

        SendMessage message = SendMessage.builder()
                .chatId(channelId)
                .text(text)
                .build();

        try {
            telegramClient.execute(message);
            log.info("Уведомление о ревью отправлено в Telegram. runId={}, mr=!{}", event.runId(), event.mrIid());
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить уведомление в Telegram. runId={}, mr=!{}, err={}",
                    event.runId(), event.mrIid(), e.getMessage(), e);
        }
    }

    private String buildMessage(ReviewFinishedEvent event) {
        String urlPart = (event.mrUrl() == null || event.mrUrl().isBlank())
                ? ""
                : ("\nMR: " + event.mrUrl());

        if (event.success()) {
            return """
                    ✅ Code Review завершён
                    Project: %d
                    MR: !%d
                    Title: %s
                    Score: %d/10
                    Files: %d
                    Run: %s%s
                    """.formatted(
                    event.projectId(),
                    event.mrIid(),
                    safe(event.mrTitle()),
                    event.score(),
                    event.filesChanged(),
                    event.runId(),
                    urlPart
            );
        }

        return """
                ❌ Code Review завершился ошибкой
                Project: %d
                MR: !%d
                Title: %s
                Files: %d
                Run: %s%s
                """.formatted(
                event.projectId(),
                event.mrIid(),
                safe(event.mrTitle()),
                event.filesChanged(),
                event.runId(),
                urlPart
        );
    }

    private String limit(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= TELEGRAM_SAFE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, TELEGRAM_SAFE_LIMIT - 1) + "…";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
