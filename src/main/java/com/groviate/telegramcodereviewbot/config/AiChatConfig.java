package com.groviate.telegramcodereviewbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурационный класс для создания бина ChatClient
 */
@Configuration
@Slf4j
public class AiChatConfig {

    @Bean
    public ChatClient chatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .build(); // Возвращаем готовый ChatClient
    }

    @Bean
    public String logAiConfigInitialization() {
        log.info("AI Chat Configuration initialized");
        log.info("Model: GPT-4o-mini");
        return "AI Chat Configuration initialized";
    }
}