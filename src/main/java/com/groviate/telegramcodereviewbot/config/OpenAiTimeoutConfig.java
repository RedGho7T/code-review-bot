package com.groviate.telegramcodereviewbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Конфигурация для увеличения timeout OpenAI API
 *
 */
@Configuration
@Slf4j
public class OpenAiTimeoutConfig {

    /**
     * Глобальный кастомайзер для всех RestClient
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> {
            log.info("Настраиваем RestClient с увеличенным timeout для OpenAI");

            // Создаём SimpleClientHttpRequestFactory с большим timeout
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

            requestFactory.setConnectTimeout(30 * 1000);      // 30 сек подключение
            requestFactory.setReadTimeout(180 * 1000);        // 180 сек ответ

            // Применяем ко всем RestClient
            restClientBuilder.requestFactory(requestFactory);

            log.info("RestClient timeout настроен: 180 сек (3 минуты)");
        };
    }
}