package com.groviate.telegramcodereviewbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Конфигурирует пул потоков для асинхронного выполнения ревью MR-ов.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2); //2 готовых потока
        ex.setMaxPoolSize(4); // максимум потоков в пулле
        ex.setQueueCapacity(200); // очередь до 200
        ex.setThreadNamePrefix("mr-review-");
        ex.initialize();
        return ex;
    }

    @Bean(name = "telegramExecutor")
    public Executor telegramExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("tg-");
        ex.initialize();
        return ex;
    }
}