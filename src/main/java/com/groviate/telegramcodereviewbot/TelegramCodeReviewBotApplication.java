package com.groviate.telegramcodereviewbot;

import com.groviate.telegramcodereviewbot.service.TelegramBotService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication(scanBasePackages = "com.groviate.telegramcodereviewbot")
@EnableJpaRepositories(basePackages = "com.groviate.telegramcodereviewbot.repository")
@EntityScan(basePackages = "com.groviate.telegramcodereviewbot.entity")
@EnableScheduling
public class TelegramCodeReviewBotApplication {

    public static void main(String[] args) {
        System.out.println("Запуск Telegram бота");
        System.out.println("========================================");

        // Запускаем Spring Boot приложение
        ConfigurableApplicationContext context = SpringApplication.run(TelegramCodeReviewBotApplication.class, args);

        try {
            // Получаем бота из Spring контекста
            TelegramBotService botService = context.getBean(TelegramBotService.class);

            // Регистрируем бота в Telegram API
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(botService);

            System.out.println("\nБот успешно запущен!");
            System.out.println("Имя бота: " + botService.getBotUsername());
            System.out.println("\nТеперь можете писать боту в Telegram!");

        } catch (
                TelegramApiException e) {
            System.err.println("Ошибка Telegram: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Общая ошибка: " + e.getMessage());
            e.printStackTrace();
            context.close();
        }
    }

}
