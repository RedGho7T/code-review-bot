package com.groviate.telegramcodereviewbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Configuration
public class TelegramClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true", matchIfMissing = false)
    public TelegramClient telegramClient(TelegramProperties props) {

        String token = props.getBotToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("telegram.enabled=true, но token пустой. Укажи TELEGRAM_BOT_TOKEN");
        }
        log.info("TelegramClient initialized");
        return new OkHttpTelegramClient(token);
    }
}