package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.CodeSuggestion;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.model.SuggestionSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сервис для планирования встроенных (inline) комментариев в GitLab MR
 * <p>
 * Выбирает наиболее важные suggestions (CRITICAL, WARNING) для публикации как inline комментарии,
 * соблюдая лимиты: maxInlineComments (общий), maxInlineCommentsPerFile.
 * <p>
 * Алгоритм:
 * <ol>
 *   <li>Фильтрует suggestions по severity (CRITICAL/WARNING) и валидности данных</li>
 *   <li>Сортирует по severity (CRITICAL первыми)</li>
 *   <li>Pass 1: выбирает комментарии соблюдая maxPerFile</li>
 *   <li>Pass 2: если не добрали до maxTotal - разрешает overflow по файлам</li>
 *   <li>Проверяет что номер строки валиден для inline comment (есть в diff)</li>
 *   <li>Удаляет дубли по (filePath + lineNumber + category)</li>
 * </ol>
 */
@Service
@Slf4j
public class InlineCommentPlannerService {

    private static final Pattern HUNK_PATTERN =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private static final class SelectionState {
        final List<InlineComment> selected = new ArrayList<>();
        final Set<String> dedupe = new HashSet<>();
        final Map<String, Integer> perFileCount = new HashMap<>();
        final Map<String, Map<Integer, Integer>> newToOldLineByNewPath = new HashMap<>();
    }

    /**
     * Планирует список inline комментариев из результата ревью
     *
     * <ol>
     *   <li>Pass 1: соблюдаем строгий лимит maxPerFile</li>
     *   <li>Pass 2: если не набрали maxTotal - разрешаем overflow по файлам</li>
     * </ol>
     *
     * @param result - CodeReviewResult с suggestions от OpenAI
     * @param diffs  - список MergeRequestDiff с информацией о файлах и изменениях
     * @param props  - свойства с лимитами (maxInlineComments, maxInlineCommentsPerFile, maxInlineCommentChars)
     * @return список InlineComment для публикации, отсортированный по severity (CRITICAL первыми)
     */
    public List<InlineComment> plan(CodeReviewResult result,
                                    List<MergeRequestDiff> diffs,
                                    CodeReviewProperties props) {

        if (result == null || result.getSuggestions() == null || result.getSuggestions().isEmpty()) {
            return List.of();
        }
        if (diffs == null || diffs.isEmpty()) {
            return List.of();
        }

        Map<String, MergeRequestDiff> diffByPath = indexDiffsByPath(diffs);
        List<CodeSuggestion> sorted = sortCandidates(result.getSuggestions());
        if (sorted.isEmpty()) return List.of();

        Limits limits = Limits.from(props);

        SelectionState state = new SelectionState();
        PickContext ctx = new PickContext(diffByPath, state, limits);

        // pass 1: соблюдаем maxPerFile
        pick(sorted, ctx, true);

        // pass 2: если не добрали — разрешаем overflow по файлам
        if (state.selected.size() < limits.maxTotal) {
            pick(sorted, ctx, false);
        }

        return state.selected;
    }

    /**
     * Индексирует diffs по путям файлов для быстрого поиска
     *
     * @param diffs - список MergeRequestDiff
     * @return Map: filePath -> MergeRequestDiff (содержит и oldPath и newPath)
     */
    private static Map<String, MergeRequestDiff> indexDiffsByPath(List<MergeRequestDiff> diffs) {
        Map<String, MergeRequestDiff> diffByPath = new HashMap<>();
        for (MergeRequestDiff d : diffs) {
            if (d.getNewPath() != null && !d.getNewPath().isBlank()) diffByPath.put(d.getNewPath(), d);
            if (d.getOldPath() != null && !d.getOldPath().isBlank()) diffByPath.put(d.getOldPath(), d);
        }
        return diffByPath;
    }

    /**
     * Фильтрует и сортирует suggestions для inline комментариев
     * <p>
     * Фильтры:
     * - severity = CRITICAL или WARNING (INFO не публикуем inline)
     * - fileName не null и не пустой
     * - lineNumber > 0
     * - message не пустой
     * <p>
     * Сортировка: CRITICAL (вес 3) -> WARNING (вес 2) -> INFO (вес 1)
     *
     * @param suggestions - все suggestions от OpenAI
     * @return отфильтрованный и отсортированный список кандидатов
     */
    private static List<CodeSuggestion> sortCandidates(List<CodeSuggestion> suggestions) {
        List<CodeSuggestion> candidates = suggestions.stream()
                .filter(s -> s.getSeverity() == SuggestionSeverity.CRITICAL ||
                        s.getSeverity() == SuggestionSeverity.WARNING)
                .filter(s -> s.getFileName() != null && !s.getFileName().isBlank())
                .filter(s -> s.getLineNumber() != null && s.getLineNumber() > 0)
                .filter(s -> s.getMessage() != null && !s.getMessage().isBlank())
                .toList();

        if (candidates.isEmpty()) return List.of();

        List<CodeSuggestion> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((CodeSuggestion s) -> severityWeight(s.getSeverity())).reversed());
        return sorted;
    }

    /**
     * Проходит по отсортированным suggestions и выбирает для inline комментариев
     *
     * @param sorted              - отсортированные CodeSuggestion
     * @param ctx                 - контекст с diffByPath, state, limits
     * @param enforcePerFileLimit - true для Pass 1 (соблюдать maxPerFile),
     *                            false для Pass 2 (разрешить overflow)
     */
    private void pick(List<CodeSuggestion> sorted, PickContext ctx, boolean enforcePerFileLimit) {
        for (CodeSuggestion s : sorted) {
            if (ctx.state.selected.size() >= ctx.limits.maxTotal) {
                return;
            }

            Optional<InlineComment> maybe = toInlineComment(s, ctx, enforcePerFileLimit);
            if (maybe.isPresent()) {
                InlineComment comment = maybe.get();
                ctx.state.selected.add(comment);
                ctx.state.perFileCount.merge(comment.newPath(), 1, Integer::sum);
            }
        }
    }

    /**
     * Пытается преобразовать CodeSuggestion в InlineComment
     * <p>
     * Проверяет:
     * <ol>
     *   <li>Находит diff для файла (по fileName из suggestion)</li>
     *   <li>Проверяет лимит maxPerFile (если enforcePerFileLimit=true)</li>
     *   <li>Проверяет что lineNumber валиден </li>
     *   <li>Удаляет дубли по (filePath + lineNumber + category)</li>
     *   <li>Форматирует body комментария</li>
     * </ol>
     *
     * @param s                   - CodeSuggestion от OpenAI
     * @param ctx                 - контекст выбора
     * @param enforcePerFileLimit - соблюдать ли maxPerFile
     * @return Optional.of(InlineComment) если успешно, Optional.empty() если пропускаем
     */
    private Optional<InlineComment> toInlineComment(CodeSuggestion s,
                                                    PickContext ctx, boolean enforcePerFileLimit) {
        Optional<MergeRequestDiff> maybeDiff = resolveDiffForSuggestion(s, ctx.diffByPath);
        if (maybeDiff.isEmpty()) return Optional.empty();

        MergeRequestDiff diff = maybeDiff.get();

        String newPath = resolveNewPath(diff);
        if (newPath == null || newPath.isBlank()) return Optional.empty();

        if (enforcePerFileLimit && isFileLimitReached(newPath, ctx.state.perFileCount, ctx.limits.maxPerFile)) {
            return Optional.empty();
        }

        Map<Integer, Integer> newToOld =
                getOrComputeNewToOldLineMapping(newPath, diff.getDiff(), ctx.state.newToOldLineByNewPath);

        Set<Integer> validNewLines = newToOld.keySet();

        Integer newLine = fixLineNumber(s.getLineNumber(), validNewLines);
        if (newLine == null) return Optional.empty();

        if (!newToOld.containsKey(newLine)) return Optional.empty();

        Integer oldLine = newToOld.get(newLine);

        String categoryKey = (s.getCategory() == null) ? "NA" : s.getCategory().name();
        String key = newPath + "#" + newLine + "#" + categoryKey;

        if (!ctx.state.dedupe.add(key)) {
            return Optional.empty();
        }

        String oldPath = resolveOldPath(diff, newPath);
        String body = formatInlineBody(s, ctx.limits.maxChars);

        return Optional.of(new InlineComment(oldPath, newPath, oldLine, newLine, body));
    }

    /**
     * Находит diff для suggestion по fileName
     * <p>
     * Сначала точное совпадение, потом loose match (по имени файла без пути).
     *
     * @param s          - CodeSuggestion
     * @param diffByPath - индекс diffs
     * @return Optional.of(MergeRequestDiff) если найден
     */
    private static Optional<MergeRequestDiff> resolveDiffForSuggestion(CodeSuggestion s,
                                                                       Map<String, MergeRequestDiff> diffByPath) {
        String rawPath = normalizePath(s.getFileName());
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();

        MergeRequestDiff direct = diffByPath.get(rawPath);
        if (direct != null) return Optional.of(direct);

        MergeRequestDiff loose = tryFindByLooseMatch(diffByPath, rawPath);
        return Optional.ofNullable(loose);
    }

    /**
     * Получает newPath из diff (приоритет newPath, fallback oldPath)
     */
    private static String resolveNewPath(MergeRequestDiff diff) {
        return diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();
    }

    /**
     * Получает oldPath из diff (приоритет oldPath, fallback newPath)
     */
    private static String resolveOldPath(MergeRequestDiff diff, String fallback) {
        return diff.getOldPath() != null ? diff.getOldPath() : fallback;
    }

    /**
     * Проверяет достигнут ли лимит комментариев для файла
     */
    private static boolean isFileLimitReached(String newPath, Map<String,
            Integer> perFileCount, int maxPerFile) {
        return perFileCount.getOrDefault(newPath, 0) >= maxPerFile;
    }

    private static Map<Integer, Integer> getOrComputeNewToOldLineMapping(
            String newPath,
            String diffText,
            Map<String, Map<Integer, Integer>> cache) {

        Map<Integer, Integer> cached = cache.get(newPath);
        if (cached != null) return cached;

        Map<Integer, Integer> computed = collectNewToOldLineMapping(diffText);
        cache.put(newPath, computed);
        return computed;
    }

    /**
     * Возвращает вес severity для сортировки (CRITICAL=3, WARNING=2, INFO=1)
     */
    private static int severityWeight(SuggestionSeverity s) {
        if (s == null) return 0;
        return switch (s) {
            case CRITICAL -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }

    /**
     * Нормализует путь файла (удаляет "File:", backticks, ./, leading /)
     *
     * @param p - путь из suggestion.fileName
     * @return нормализованный путь
     */
    private static String normalizePath(String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.startsWith("File:")) s = s.substring("File:".length()).trim();
        s = s.replace("`", "").trim();
        if (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    /**
     * Пытается найти diff по loose match (по имени файла без пути)
     * <p>
     * Используется если точное совпадение не найдено.
     *
     * @param diffByPath - индекс diffs
     * @param rawPath    - нормализованный путь из suggestion
     * @return MergeRequestDiff если найден match, иначе null
     */
    private static MergeRequestDiff tryFindByLooseMatch(Map<String, MergeRequestDiff> diffByPath,
                                                        String rawPath) {
        if (rawPath == null) return null;
        String fileNameOnly = rawPath.contains("/") ? rawPath.
                substring(rawPath.lastIndexOf('/') + 1) : rawPath;

        for (Map.Entry<String, MergeRequestDiff> e : diffByPath.entrySet()) {
            String key = e.getKey();
            if (key != null && key.endsWith("/" + fileNameOnly)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Проверяет, является ли строка file header (+++ или ---)
     */
    private static boolean isDiffFileHeader(String line) {
        return line.startsWith("+++ ")
                || line.startsWith("--- ");
    }


    /**
     * Корректирует номер строки если он невалиден (мягкий fallback +/- 2)
     * <p>
     * Если proposed валиден - возвращает его.
     * Иначе пытается найти ближайший валидный в диапазоне +/- 2.
     *
     * @param proposed - номер строки из suggestion
     * @param valid    - Set валидных номеров строк
     * @return скорректированный номер или null если не нашли валидный
     */
    private static Integer fixLineNumber(Integer proposed, Set<Integer> valid) {
        if (proposed == null || proposed <= 0) return null;
        if (valid.contains(proposed)) return proposed;

        // мягкий fallback: +/- 2
        for (int d = 1; d <= 2; d++) {
            if (valid.contains(proposed - d)) return proposed - d;
            if (valid.contains(proposed + d)) return proposed + d;
        }
        return null;
    }

    /**
     * Форматирует body inline комментария в Markdown
     * Обрезает до maxChars если превышен лимит.
     *
     * @param s        - CodeSuggestion
     * @param maxChars - максимум символов (например, 1200)
     * @return отформатированный Markdown текст
     */
    private static String formatInlineBody(CodeSuggestion s, int maxChars) {
        String sev = s.getSeverity() == null ? "INFO" : s.getSeverity().name();
        String icon = switch (sev) {
            case "CRITICAL" -> "🔴";
            case "WARNING" -> "🟠";
            default -> "ℹ️";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(" **").append(sev).append("**");
        if (s.getCategory() != null) sb.append(" • ").append(s.getCategory().name());
        sb.append("\n\n").append(s.getMessage().trim());

        if (s.getSuggestionFix() != null && !s.getSuggestionFix().isBlank()) {
            String fix = s.getSuggestionFix().trim();
            if (fix.length() > 400) fix = fix.substring(0, 400) + "…";
            sb.append("\n\n**Suggested fix:**\n").append(fix);
        }

        String res = sb.toString();
        if (res.length() > maxChars) {
            res = res.substring(0, Math.max(0, maxChars - 1)) + "…";
        }
        return res;
    }

    /**
     * DTO для inline комментария
     *
     * @param oldPath - путь файла в базовой ветке (может быть null для новых файлов)
     * @param newPath - путь файла в текущей ветке
     * @param newLine - номер строки в новой версии файла
     * @param body    - текст комментария в Markdown
     */
    public record InlineComment(String oldPath, String newPath, Integer oldLine, Integer newLine, String body) { }


    /**
     * DTO для лимитов inline комментариев
     *
     * @param maxTotal   - максимум inline комментариев всего (например, 10)
     * @param maxPerFile - максимум inline комментариев на файл (например, 3)
     * @param maxChars   - максимум символов в одном комментарии (например, 1200)
     */
    private record Limits(int maxTotal, int maxPerFile, int maxChars) {

        static Limits from(CodeReviewProperties props) {
            int maxTotal = Optional.ofNullable(props.getMaxInlineComments()).orElse(10);
            int maxPerFile = Optional.ofNullable(props.getMaxInlineCommentsPerFile()).orElse(3);
            int maxChars = Optional.ofNullable(props.getMaxInlineCommentChars()).orElse(1200);
            return new Limits(maxTotal, maxPerFile, maxChars);
        }
    }

    private static Map<Integer, Integer> collectNewToOldLineMapping(String diff) {
        if (diff == null || diff.isBlank()) return Map.of();

        Map<Integer, Integer> mapping = new HashMap<>();

        int currentOldLine = -1;
        int currentNewLine = -1;

        String[] lines = diff.split("\n", -1);
        for (String line : lines) {
            HunkStart hunk = parseHunkStarts(line);
            boolean shouldSkip = false;

            if (hunk != null) {
                currentOldLine = hunk.oldStart();
                currentNewLine = hunk.newStart();
                shouldSkip = true;
            } else if (!isInHunk(currentOldLine, currentNewLine)) {
                shouldSkip = true;
            } else if (isDiffFileHeader(line)) {
                shouldSkip = true;
            }

            if (shouldSkip) {
                continue; //
            }

            LineCounters updated = processLineForMapping(line, currentOldLine, currentNewLine, mapping);
            currentOldLine = updated.oldLine();
            currentNewLine = updated.newLine();
        }

        return mapping;
    }

    private static boolean isInHunk(int oldLine, int newLine) {
        return oldLine > 0 && newLine > 0;
    }

    private record HunkStart(int oldStart, int newStart) { }

    private static HunkStart parseHunkStarts(String line) {
        Matcher m = HUNK_PATTERN.matcher(line);
        if (!m.matches()) return null;

        int oldStart = Integer.parseInt(m.group(1));
        int newStart = Integer.parseInt(m.group(3));
        return new HunkStart(oldStart, newStart);
    }

    private record LineCounters(int oldLine, int newLine) { }

    private static LineCounters processLineForMapping(String line,
                                                      int currentOldLine,
                                                      int currentNewLine,
                                                      Map<Integer, Integer> mapping) {

        // Контекст: есть и old, и new
        if (line.startsWith(" ")) {
            mapping.put(currentNewLine, currentOldLine);
            return new LineCounters(currentOldLine + 1, currentNewLine + 1);
        }

        // Добавление: есть new, old отсутствует (null)
        if (line.startsWith("+")) {
            mapping.put(currentNewLine, null);
            return new LineCounters(currentOldLine, currentNewLine + 1);
        }

        // Удаление: сдвигаем только old
        if (line.startsWith("-")) {
            return new LineCounters(currentOldLine + 1, currentNewLine);
        }

        // Нестандартная строка (например "\ No newline at end of file") — не двигаем
        return new LineCounters(currentOldLine, currentNewLine);
    }

    /**
     * Контекст для передачи в методы выбора (иммутабельный)
     */
    private record PickContext(Map<String, MergeRequestDiff> diffByPath, SelectionState state, Limits limits) {
    }
}
