package com.groviate.telegramcodereviewbot.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

/**
 * Конфигурация для HTTP клиента OkHttp
 */
@Configuration
public class HttpClientConfig {


    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                // Timeout для подключения (сколько ждать подключения)
                .connectTimeout(10, TimeUnit.SECONDS)

                // Timeout для чтения ответа (сколько ждать данных)
                .readTimeout(30, TimeUnit.SECONDS)

                // Timeout для записи запроса
                .writeTimeout(30, TimeUnit.SECONDS)

                // Строим и возвращаем готовый клиент
                .build();
    }
}