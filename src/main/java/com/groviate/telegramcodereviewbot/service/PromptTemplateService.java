package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис для подготовки инструкций для AI
 * <p>
 * 1. Получить список измененных файлов (MergeRequestDiff)
 * 2. Форматировать их в красивый текст с кодом
 * 3. Добавить системные инструкции (как должен работать AI)
 * 4. Вернуть готовый промпт для отправки в GPT
 */
@Service
@Slf4j
public class PromptTemplateService {

    @Getter
    private final String systemPrompt;

    private final String userPromptTemplate;

    /**
     * @param systemPromptResource       - Resource с содержимым system-prompt.txt
     * @param userPromptTemplateResource - Resource с содержимым user-prompt.txt
     * @throws IllegalStateException если файлы не найдены в src/main/resources/prompts/
     */
    public PromptTemplateService(
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource,
            @Value("classpath:prompts/user-prompt.txt") Resource userPromptTemplateResource
    ) {

        try {
            //Читаем файл system-prompt.txt в одну строку и декодирует как UTF-8
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
            log.info("Системный промпт загружен из файла, размер: {} символов", systemPrompt.length());

            this.userPromptTemplate = userPromptTemplateResource.getContentAsString(StandardCharsets.UTF_8);
            log.info("Шаблон пользовательского промпта загружен, размер: {} символов", userPromptTemplate.length());
        } catch (Exception e) {
            log.error("Не удалось загрузить промпты из файлов", e);
            throw new IllegalStateException("""
                    Не удалось загрузить промпты из resources/prompts/
                    Проверь что существуют файлы:
                    - src/main/resources/prompts/system-prompt.txt
                    - src/main/resources/prompts/user-prompt.txt
                    """, e);
        }
    }

    /**
     * Подготавливает полный пользовательский промпт для анализа кода
     *
     * @param diffs       - список объектов MergeRequestDiff с информацией об изменениях
     * @param title       - заголовок Merge Request
     * @param description - описание Merge Request (может быть null)
     * @return готовый пользовательский промпт для отправки в OpenAI
     */
    public String preparePrompt(List<MergeRequestDiff> diffs, String title, String description) {
        log.debug("Подготавливаю промпт для анализа {} измененных файлов", diffs.size());

        // Шаг 1: Форматировать каждый diff в строку
        String formattedDiffs = diffs.stream()
                // Для каждого измененного файла создаем строку
                .map(this::formatDiff)
                // Объединяем все в одну большую строку с разделителями
                .collect(Collectors.joining("\n" + "=".repeat(80) + "\n"));

        // Шаг 2: Собрать финальный промпт
        String userPrompt = userPromptTemplate
                .replace("{title}", title)
                .replace("{description}", description != null ? description : "Нет описания")
                .replace("{diffs}", formattedDiffs);

        log.debug("Промпт успешно подготовлен, размер: {} символов", userPrompt.length());
        return userPrompt;
    }

    /**
     * Форматирует информацию об одном изменённом файле в читаемый текст
     * <p>
     * 1. Определяет путь к файлу (старый / новый)
     * 2. Определяет статус (ADDED, DELETED, RENAMED, MODIFIED)
     * 3. Добавляет текст самих изменений (diff)
     * 4. Возвращает красиво отформатированную строку
     *
     * @param diff - объект MergeRequestDiff с информацией о файле и изменениях
     * @return отформатированная строка с информацией об одном файле
     */
    private String formatDiff(MergeRequestDiff diff) {
        StringBuilder sb = new StringBuilder();

        String filePath = diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();

        sb.append(String.format("File: %s%n", filePath));

        if (diff.isNewFile()) {
            sb.append("Status: ADDED (новый файл)\n");
        } else if (diff.isDeletedFile()) {
            sb.append("Status: DELETED (удален)\n");
        } else if (diff.isRenamedFile()) {
            sb.append(String.format("Status: RENAMED (переименован из %s)%n", diff.getOldPath()));
        } else {
            sb.append("Status: MODIFIED (изменен)\n");
        }

        if (diff.getDiff() != null && !diff.getDiff().isEmpty()) {
            sb.append("\nCode Changes:\n");
            sb.append(diff.getDiff()).append("\n");
        }
        return sb.toString();
    }
}
