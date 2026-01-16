package com.groviate.telegramcodereviewbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.groviate.telegramcodereviewbot")
@EnableJpaRepositories(basePackages = "com.groviate.telegramcodereviewbot.repository")
@EntityScan(basePackages = "com.groviate.telegramcodereviewbot.entity")
@EnableScheduling
public class TelegramCodeReviewBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelegramCodeReviewBotApplication.class, args);
    }

}
