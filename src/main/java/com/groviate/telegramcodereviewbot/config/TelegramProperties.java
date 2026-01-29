package com.groviate.telegramcodereviewbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
@Data
public class TelegramProperties {
    private boolean enabled = false;
    private boolean longPollingEnabled = false;
    private String botToken;
    private String botUsername;
    private String channelId;
}