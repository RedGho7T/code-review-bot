package com.groviate.telegramcodereviewbot.config;

import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

@Configuration
public class TgBotConfig {
    private static final String CONFIG_FILE = "application.yml";

    private String botToken;
    private String botUsername;

    public TgBotConfig() {
        loadConfig();
        System.out.println("✅ Конфиг загружен. Бот: " + botUsername);
    }

    private void loadConfig() {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = getClass()
                .getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {

            if (inputStream == null) {
                throw new RuntimeException("Файл конфигурации не найден: " + CONFIG_FILE);
            }

            Map<String, Object> config = yaml.load(inputStream);
            Map<String, String> telegramConfig = (Map<String, String>) config.get("telegram");

            this.botToken = telegramConfig.get("botToken");
            this.botUsername = telegramConfig.get("botUsername");

            // Переопределение переменными окружения
            String envToken = System.getenv("TELEGRAM_BOT_TOKEN");
            if (envToken != null && !envToken.isEmpty()) {
                this.botToken = envToken;
                System.out.println("Токен переопределен из переменной окружения");
            }

        } catch (Exception e) {
            throw new RuntimeException("Ошибка загрузки конфигурации", e);
        }
    }

    // Геттеры
    public String getBotToken() {
        return botToken;
    }

    public String getBotUsername() {
        return botUsername;
    }
}