package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
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
    private final CodeReviewProperties props;

    /**
     * @param systemPromptResource       - Resource с содержимым system-prompt.txt
     * @param userPromptTemplateResource - Resource с содержимым user-prompt.txt
     * @throws IllegalStateException если файлы не найдены в src/main/resources/prompts/
     */
    public PromptTemplateService(
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource,
            @Value("classpath:prompts/user-prompt.txt") Resource userPromptTemplateResource,
            CodeReviewProperties props
    ) {
        this.props = props;

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
    public String preparePrompt(List<MergeRequestDiff> diffs,
                                String title,
                                String description,
                                String ragContext) {
        log.debug("Подготавливаю промпт для анализа {} измененных файлов", diffs.size());

        if (ragContext == null || ragContext.trim().isEmpty()) {
            log.warn("RAG контекст пустой");
            ragContext = "Стандарты не найдены";
        }

        // Шаг 1: Форматировать каждый diff в строку + контролируем сборку по ограничениям
        int maxFiles = props.getMaxFilesPerReview();
        int maxTotalDiffChars = props.getMaxDiffCharsTotal();

        String separator = "\n" + "=".repeat(80) + "\n";
        StringBuilder diffsSb = new StringBuilder();

        int includedFiles = 0;
        int omittedFiles = 0;

        for (MergeRequestDiff d : diffs) {

            // 1) Пропускаем удалённые файлы (обычно diff не нужен)
            if (d.isDeletedFile()) {
                omittedFiles++;
                continue;
            }

            // 2) Пропускаем пустые diffs
            String rawDiff = d.getDiff();
            if (rawDiff == null || rawDiff.isBlank()) {
                omittedFiles++;
                continue;
            }

            // 3) Ограничение по количеству файлов
            if (includedFiles >= maxFiles) {
                omittedFiles++;
                continue;
            }

            // 4) Форматируем один файл (ВАЖНО: formatDiff внутри уже применяет limitDiff)
            String one = formatDiff(d);

            // 5) Проверяем, влезет ли этот кусок в общий лимит по символам
            int extra = (diffsSb.length() == 0)
                    ? one.length()
                    : separator.length() + one.length();

            if (diffsSb.length() + extra > maxTotalDiffChars) {
                omittedFiles++;
                continue;
            }

            // 6) Добавляем separator между файлами
            if (diffsSb.length() > 0) {
                diffsSb.append(separator);
            }
            diffsSb.append(one);

            includedFiles++;
        }

        String formattedDiffs = diffsSb.toString();

        if (omittedFiles > 0) {
            formattedDiffs += "\n\n...(пропущено файлов: " + omittedFiles + " из-за лимитов)\n";
        }

        // Шаг 2: Собрать финальный промпт
        String prompt = userPromptTemplate
                .replace("{title}", title != null ? title : "No title")
                .replace("{description}", description != null ? description : "Нет описания")
                .replace("{rag_context}", ragContext)
                .replace("{code_changes}", formattedDiffs)
                .replace("{diffs}", formattedDiffs);

        int maxPrompt = props.getMaxPromptCharsTotal();
        if (prompt.length() > maxPrompt) {
            prompt = prompt.substring(0, maxPrompt) + "\n...(prompt обрезан по лимиту)\n";
        }

        log.info("Статистика итогового промта: includedFiles={}, omittedFiles={}, diffsChars={}, " +
                        "ragChars={}, promptChars={}",
                includedFiles, omittedFiles, formattedDiffs.length(), ragContext.length(), prompt.length());

        return prompt;
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

        String limited = limitDiff(diff.getDiff());
        if (!limited.isBlank()) {
            sb.append("\nCode Changes:\n");
            sb.append(limited).append("\n");
        }
        return sb.toString();
    }

    private String limitDiff(String diff) {
        if (diff == null || diff.isBlank()) return "";

        // лимит по символам на файл
        int maxChars = props.getMaxDiffCharsPerFile();
        String d = diff.length() > maxChars ? diff.substring(0, maxChars) + "\n...(diff обрезан)\n" : diff;

        // лимит по строкам на файл
        int maxLines = props.getMaxLinesPerFile();
        String[] lines = d.split("\n");
        if (lines.length <= maxLines) return d;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append("\n");
        sb.append("...(diff обрезан по строкам: ").append(lines.length).append(" -> ").append(maxLines).append(")\n");
        return sb.toString();
    }
}
