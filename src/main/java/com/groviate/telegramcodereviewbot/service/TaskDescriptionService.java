package com.groviate.telegramcodereviewbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TaskDescriptionService {

    private static final String DEFAULT_DESCRIPTION = "Описание отсутствует";
    private static final String CLASSPATH_PREFIX = "classpath:task-descriptions/";
    private static final String EXT = ".md";

    private final ResourceLoader resourceLoader;

    // кешируем содержимое файлов, чтобы не читать их каждый раз
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public TaskDescriptionService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String getTaskDescription(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return DEFAULT_DESCRIPTION;
        }
        return cache.computeIfAbsent(taskId, this::loadFromResources);
    }

    private String loadFromResources(String taskId) {
        String location = CLASSPATH_PREFIX + taskId + EXT;
        Resource resource = resourceLoader.getResource(location);

        if (!resource.exists()) {
            log.warn("Не найден файл описания задачи: {}", location);
            return DEFAULT_DESCRIPTION;
        }

        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Ошибка чтения файла описания задачи: {}", location, e);
            return DEFAULT_DESCRIPTION;
        }
    }
}
