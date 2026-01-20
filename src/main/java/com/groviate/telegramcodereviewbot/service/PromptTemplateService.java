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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern HUNK_PATTERN =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

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

        List<MergeRequestDiff> safeDiffs = diffs == null ? List.of() : diffs;
        log.debug("Подготавливаю промпт для анализа {} измененных файлов", safeDiffs.size());

        if (!props.isRagEnabled()) {
            ragContext = "";
        } else if (ragContext == null || ragContext.isBlank()) {
            log.warn("RAG включен, но контекст не найден");
            ragContext = "Стандарты не найдены";
        }

        int maxFiles = props.getMaxFilesPerReview();
        int maxTotalDiffChars = props.getMaxDiffCharsTotal();

        String separator = "\n" + "=".repeat(80) + "\n";
        StringBuilder diffsSb = new StringBuilder();

        int includedFiles = 0;
        int omittedFiles = 0;

        for (MergeRequestDiff d : safeDiffs) {

            if (shouldOmitDiff(d) || includedFiles >= maxFiles) {
                omittedFiles++;
                continue;
            }

            String one = formatDiff(d);

            if (appendIfFits(diffsSb, one, separator, maxTotalDiffChars)) {
                includedFiles++;
            } else {
                omittedFiles++;
            }
        }

        String formattedDiffs = diffsSb.toString();
        if (omittedFiles > 0) {
            formattedDiffs += "\n\n...(пропущено файлов: " + omittedFiles + " из-за лимитов)\n";
        }

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

        log.info("Статистика итогового промта: " +
                        "includedFiles={}, omittedFiles={}, diffsChars={}, ragChars={}, promptChars={}",
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

        String annotated = annotateUnifiedDiff(diff.getDiff());

        String limited = limitDiff(annotated);

        if (!limited.isBlank()) {
            sb.append("\nCode Changes (annotated with new_line):\n");
            sb.append(limited).append("\n");
        }
        return sb.toString();
    }

    /**
     * Ограничивает размер diff по символам и строкам
     *
     * @param diff - исходный diff текст
     * @return обрезанный diff если превышены лимиты, иначе оригинал
     */
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

    /**
     * Аннотирует унифицированный diff с номерами новых строк
     * <p>
     * Преобразует обычный diff в версию с номерами строк для удобства AI.
     *
     * @param diff - унифицированный diff текст
     * @return аннотированный diff с номерами строк
     */
    private String annotateUnifiedDiff(String diff) {
        if (diff == null || diff.isBlank()) return "";

        StringBuilder out = new StringBuilder();

        int oldLine = -1;
        int newLine = -1;
        boolean inHunk = false;

        String[] lines = diff.split("\n", -1);

        for (String line : lines) {
            Matcher m = HUNK_PATTERN.matcher(line);

            if (m.matches()) {
                oldLine = Integer.parseInt(m.group(1));
                newLine = Integer.parseInt(m.group(3));
                inHunk = true;
                out.append(line).append("\n");
            } else if (!inHunk || oldLine < 0 || newLine < 0) {
                out.append(line).append("\n");
            } else {
                int[] updated = annotateHunkLine(out, line, oldLine, newLine);
                oldLine = updated[0];
                newLine = updated[1];
            }
        }

        return out.toString();
    }


    /**
     * Проверяет нужно ли пропустить diff для файла
     *
     * @param d - MergeRequestDiff для проверки
     * @return true если файл удален или diff пустой
     */
    private static boolean shouldOmitDiff(MergeRequestDiff d) {
        if (d.isDeletedFile()) {
            return true;
        }
        String rawDiff = d.getDiff();
        return rawDiff == null || rawDiff.isBlank();
    }

    /**
     * Добавляет отформатированный diff в StringBuilder если хватает места
     *
     * @param diffsSb - StringBuilder с накопленными diffs
     * @param one - один отформатированный diff для добавления
     * @param separator - разделитель между diff
     * @param maxTotalDiffChars - максимальный размер всех diffs
     * @return true если diff был добавлен, false если не хватило места
     */
    private static boolean appendIfFits(StringBuilder diffsSb,
                                        String one,
                                        String separator,
                                        int maxTotalDiffChars) {
        int extra = diffsSb.isEmpty()
                ? one.length()
                : separator.length() + one.length();

        if (diffsSb.length() + extra > maxTotalDiffChars) {
            return false;
        }

        if (!diffsSb.isEmpty()) {
            diffsSb.append(separator);
        }
        diffsSb.append(one);
        return true;
    }

    /**
     * Аннотирует одну строку из hunk с номером строки
     *
     * @param out - StringBuilder для результата
     * @param line - строка для аннотации
     * @param oldLine - текущий номер старой строки
     * @param newLine - текущий номер новой строки
     * @return массив с обновленными [oldLine, newLine]
     */
    private static int[] annotateHunkLine(StringBuilder out, String line, int oldLine, int newLine) {
        if (line.isEmpty() || isFileHeaderLine(line)) {
            out.append(line).append("\n");
            return new int[]{oldLine, newLine};
        }

        char prefix = line.charAt(0);
        String content = line.length() > 1 ? line.substring(1) : "";

        switch (prefix) {
            case ' ' -> {
                out.append(" ").append(newLine).append(": ").append(content).append("\n");
                oldLine++;
                newLine++;
            }
            case '+' -> {
                out.append("+").append(newLine).append(": ").append(content).append("\n");
                newLine++;
            }
            case '-' -> {
                out.append("-").append(oldLine).append(": ").append(content).append("\n");
                oldLine++;
            }
            default -> out.append(line).append("\n");
        }

        return new int[]{oldLine, newLine};
    }

    private static boolean isFileHeaderLine(String line) {
        return line.startsWith("+++ ") || line.startsWith("--- ");
    }

}
