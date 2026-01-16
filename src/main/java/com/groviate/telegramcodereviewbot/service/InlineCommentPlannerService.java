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

@Service
@Slf4j
public class InlineCommentPlannerService {

    private static final Pattern HUNK_PATTERN =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public record InlineComment(String oldPath, String newPath, Integer newLine, String body) {
    }

    private record Limits(int maxTotal, int maxPerFile, int maxChars) {

        static Limits from(CodeReviewProperties props) {
                int maxTotal = Optional.ofNullable(props.getMaxInlineComments()).orElse(10);
                int maxPerFile = Optional.ofNullable(props.getMaxInlineCommentsPerFile()).orElse(3);
                int maxChars = Optional.ofNullable(props.getMaxInlineCommentChars()).orElse(1200);
                return new Limits(maxTotal, maxPerFile, maxChars);
            }
        }

    private static final class SelectionState {
        final List<InlineComment> selected = new ArrayList<>();
        final Set<String> dedupe = new HashSet<>();
        final Map<String, Integer> perFileCount = new HashMap<>();
        final Map<String, Set<Integer>> validNewLinesByNewPath = new HashMap<>();
    }

    private record PickContext(Map<String, MergeRequestDiff> diffByPath, SelectionState state, Limits limits) {
    }

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

    private static Map<String, MergeRequestDiff> indexDiffsByPath(List<MergeRequestDiff> diffs) {
        Map<String, MergeRequestDiff> diffByPath = new HashMap<>();
        for (MergeRequestDiff d : diffs) {
            if (d.getNewPath() != null && !d.getNewPath().isBlank()) diffByPath.put(d.getNewPath(), d);
            if (d.getOldPath() != null && !d.getOldPath().isBlank()) diffByPath.put(d.getOldPath(), d);
        }
        return diffByPath;
    }

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

        Set<Integer> validNewLines = getOrComputeValidNewLines(newPath, diff.getDiff(), ctx.state.validNewLinesByNewPath);
        Integer line = fixLineNumber(s.getLineNumber(), validNewLines);
        if (line == null) return Optional.empty();

        String categoryKey = (s.getCategory() == null) ? "NA" : s.getCategory().name();
        String key = newPath + "#" + line + "#" + categoryKey;

        if (!ctx.state.dedupe.add(key)) {
            return Optional.empty();
        }

        String oldPath = resolveOldPath(diff, newPath);
        String body = formatInlineBody(s, ctx.limits.maxChars);

        return Optional.of(new InlineComment(oldPath, newPath, line, body));
    }

    private static Optional<MergeRequestDiff> resolveDiffForSuggestion(CodeSuggestion s,
                                                                       Map<String, MergeRequestDiff> diffByPath) {
        String rawPath = normalizePath(s.getFileName());
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();

        MergeRequestDiff direct = diffByPath.get(rawPath);
        if (direct != null) return Optional.of(direct);

        MergeRequestDiff loose = tryFindByLooseMatch(diffByPath, rawPath);
        return Optional.ofNullable(loose);
    }

    private static String resolveNewPath(MergeRequestDiff diff) {
        return diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();
    }

    private static String resolveOldPath(MergeRequestDiff diff, String fallback) {
        return diff.getOldPath() != null ? diff.getOldPath() : fallback;
    }

    private static boolean isFileLimitReached(String newPath, Map<String,
            Integer> perFileCount, int maxPerFile) {
        return perFileCount.getOrDefault(newPath, 0) >= maxPerFile;
    }

    private static Set<Integer> getOrComputeValidNewLines(String newPath, String diffText,
                                                          Map<String, Set<Integer>> cache) {
        // Fix для пункта (1): без lambda (diffText может быть не effectively final в вызывающем коде)
        Set<Integer> cached = cache.get(newPath);
        if (cached != null) return cached;

        Set<Integer> computed = collectValidNewLines(diffText);
        cache.put(newPath, computed);
        return computed;
    }

    private static int severityWeight(SuggestionSeverity s) {
        if (s == null) return 0;
        return switch (s) {
            case CRITICAL -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }

    private static String normalizePath(String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.startsWith("File:")) s = s.substring("File:".length()).trim();
        s = s.replace("`", "").trim();
        if (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

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
     * Собираем множество допустимых new_line, на которые можно оставить inline comment.
     * Учитываем только строки контекста ' ' и добавления '+'. Удаления '-' не дают new_line.
     * <p>
     * Sonar fixes:
     * - снижена когнитивная сложность (разбито на маленькие методы)
     * - убраны continue/break (нет ни одного)
     */
    private static Set<Integer> collectValidNewLines(String diff) {
        if (diff == null || diff.isBlank()) return Set.of();

        Set<Integer> newLines = new HashSet<>();
        int currentNewLine = -1;

        String[] lines = diff.split("\n", -1);
        for (String line : lines) {
            Integer hunkStart = parseHunkNewStart(line);
            if (hunkStart != null) {
                currentNewLine = hunkStart;
            } else if (isInHunk(currentNewLine) && !isDiffFileHeader(line)) {
                currentNewLine = processDiffLine(line, currentNewLine, newLines);
            }
        }

        return newLines;
    }

    private static Integer parseHunkNewStart(String line) {
        Matcher m = HUNK_PATTERN.matcher(line);
        if (!m.matches()) return null;
        return Integer.parseInt(m.group(3));
    }

    private static boolean isInHunk(int currentNewLine) {
        return currentNewLine > 0;
    }

    private static boolean isDiffFileHeader(String line) {
        return line.startsWith("+++ ")
                || line.startsWith("--- ");
    }

    private static int processDiffLine(String line, int currentNewLine, Set<Integer> newLines) {
        if (line.startsWith(" ") || line.startsWith("+")) {
            newLines.add(currentNewLine);
            return currentNewLine + 1;
        }
        // '-' или что-то нестандартное не двигают new_line
        return currentNewLine;
    }

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
}
