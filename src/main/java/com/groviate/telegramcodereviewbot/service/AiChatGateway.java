package com.groviate.telegramcodereviewbot.service;

/**
 * Тонкий слой над ChatClient.
 */
public interface AiChatGateway {
    String ask(String systemPrompt, String userPrompt);
}
