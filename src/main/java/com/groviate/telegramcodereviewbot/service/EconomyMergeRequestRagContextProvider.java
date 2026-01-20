package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.config.RagConfig;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Провайдер RAG контекста в режиме экономии
 * <p>
 * Активируется при rag-economy-mode=true (по умолчанию).
 * <p>
 * Оптимизирует использование OpenAI API и лимиты промпта:
 * <ol>
 *   <li>Pass 1: один общий query embedding для всего MR (общий контекст)</li>
 *   <li>Pass 2: отдельные embeddings для top-N самых больших файлов (специальный контекст)</li>
 *   <li>Соблюдает строгие бюджеты: maxContextCharsTotal, maxContextCharsGeneral, maxContextCharsPerFile</li>
 *   <li>Удаляет дубль блоки по hashCode (не публикуем один стандарт дважды)</li>
 * </ol>
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "code-review", name = "rag-economy-mode", havingValue = "true", matchIfMissing = true)
public class EconomyMergeRequestRagContextProvider implements MergeRequestRagContextProvider {

    private static final String RAG_HEADER = "\n\n=== РЕЛЕВАНТНЫЕ СТАНДАРТЫ КОДИРОВАНИЯ ===\n\n";

    private final RagContextService ragContextService;
    private final RagConfig ragConfig;

    public EconomyMergeRequestRagContextProvider(RagContextService ragContextService, RagConfig ragConfig) {
        this.ragContextService = ragContextService;
        this.ragConfig = ragConfig;
    }

    /**
     * Собирает RAG контекст в режиме экономии
     * <p>
     * Двухэтапный процесс:
     * <ol>
     *   <li>buildGeneralSection: один query embedding для общего контекста всего MR</li>
     *   <li>buildTopFilesSection: отдельные embeddings для top-N больших файлов</li>
     *   <li>Объединяет и ограничивает по totalBudget</li>
     * </ol>
     *
     * @param diffs - список MergeRequestDiff с информацией о файлах и изменениях
     * @return RAG контекст, ограниченный по бюджетам символов
     */
    @Override
    public String buildRagContext(List<MergeRequestDiff> diffs) {
        List<MergeRequestDiff> candidates = candidates(diffs);
        if (candidates.isEmpty()) return "";

        Budgets b = budgets();
        Set<Integer> seen = new HashSet<>();

        String general = buildGeneralSection(candidates, b.generalBudget, seen);
        String perFile = buildTopFilesSection(candidates, b.topFiles, b.perFileBudget, seen);

        String combined = joinSections(general, perFile).trim();
        String limited = limitTotal(combined, b.totalBudget);

        log.info("ECONOMY: rag chars={}", limited.length());
        return limited;
    }

    /**
     * Фильтрует потенциальные файлы для анализа
     *
     * @param diffs - исходный список
     * @return отфильтрованный список (без null, без deleted, только с непустым diff)
     */
    private List<MergeRequestDiff> candidates(List<MergeRequestDiff> diffs) {
        if (diffs == null) return List.of();
        return diffs.stream()
                .filter(Objects::nonNull)
                .filter(d -> !d.isDeletedFile())
                .filter(d -> d.getDiff() != null && !d.getDiff().isBlank())
                .toList();
    }

    /**
     * Получает бюджеты из конфигурации
     *
     * @return объект Budgets с лимитами (total, general, perFile, topFiles)
     */
    private Budgets budgets() {
        return new Budgets(
                positiveOrDefault(ragConfig.getRagMaxContextCharsTotal(), 12_000),
                positiveOrDefault(ragConfig.getRagMaxContextCharsGeneral(), 4_500),
                positiveOrDefault(ragConfig.getRagMaxContextCharsPerFile(), 1_500),
                Math.max(0, ragConfig.getRagTopFiles())
        );
    }

    /**
     * Собирает общий контекст для всего MR (Pass 1)
     * <p>
     * Процесс:
     * <ol>
     *   <li>Строит один query текст из кусков всех файлов (buildGeneralQueryText)</li>
     *   <li>Отправляет в RagContextService для одного embedding запроса</li>
     *   <li>Разбивает результат на блоки (по эмодзи)</li>
     *   <li>Выбирает блоки в пределах budget (не повторяет)</li>
     * </ol>
     *
     * @param candidates - отфильтрованные diff
     * @param budget     - бюджет символов для этого раздела
     * @param seen       - Set для удаления дубль блоков по hashCode
     * @return раздел "общий контекст" с заголовком
     */
    private String buildGeneralSection(List<MergeRequestDiff> candidates, int budget, Set<Integer> seen) {
        String query = buildGeneralQueryText(candidates);
        String raw = ragContextService.getContextForCode(query);
        String normalized = stripHeader(raw);

        List<String> blocks = splitBlocks(normalized);
        String text = joinBlocksWithBudget(blocks, budget, seen);

        return text.isBlank() ? "" : "\n--- RAG: общий контекст по всему MR ---\n" + text + "\n";
    }

    /**
     * Собирает контекст для самых больших файлов (Pass 2)
     * <p>
     * Процесс:
     * <ol>
     *   <li>Отбирает top-N файлов по размеру diff</li>
     *   <li>Для каждого: отдельный embedding запрос в RagContextService</li>
     *   <li>Результаты форматируются с заголовком "[Файл: path]"</li>
     *   <li>Каждый файл соблюдает budget perFileBudget</li>
     * </ol>
     *
     * @param candidates    - отфильтрованные diff
     * @param topFiles      - количество файлов для обработки
     * @param perFileBudget - бюджет per file
     * @param seen          - Set для удаления дубль блоков
     * @return раздел "дополнительно для больших файлов" с заголовком
     */
    private String buildTopFilesSection(List<MergeRequestDiff> candidates,
                                        int topFiles, int perFileBudget, Set<Integer> seen) {
        if (topFiles <= 0) return "";

        List<MergeRequestDiff> biggest = candidates.stream()
                .sorted(Comparator.comparingInt(EconomyMergeRequestRagContextProvider::diffSize).reversed())
                .limit(topFiles)
                .toList();

        StringBuilder sb = new StringBuilder();

        for (MergeRequestDiff d : biggest) {
            String raw = ragContextService.getContextForCode(d.getDiff());
            String normalized = stripHeader(raw);

            List<String> blocks = splitBlocks(normalized);
            String text = joinBlocksWithBudget(blocks, perFileBudget, seen);

            if (!text.isBlank()) {
                sb.append("\n[Файл: ").append(filePath(d)).append("]\n");
                sb.append(text).append("\n");
            }
        }

        String section = sb.toString().trim();
        return section.isBlank() ? "" : "\n--- RAG: дополнительно для самых больших файлов ---\n" + section + "\n";
    }

    /**
     * Строит общий query текст из всех файлов для одного embedding запроса
     * <p>
     * Умно распределяет бюджет: maxQuery / numberOfFiles, но не менее 500 и не более 2500 символов на файл.
     *
     * @param diffs - список diff
     * @return query текст для embedding (объединение snippets всех файлов)
     */
    private String buildGeneralQueryText(List<MergeRequestDiff> diffs) {
        int maxQ = positiveOrDefault(ragConfig.getMaxEmbeddingQueryChars(), 15_000);

        int denom = Math.clamp(diffs.size(), 1, 10);
        int perFile = Math.clamp(maxQ / denom, 500, 2500);

        StringBuilder sb = new StringBuilder(Math.min(maxQ, 4096));

        for (MergeRequestDiff d : diffs) {
            if (sb.length() >= maxQ) return sb.toString();

            String header = "File: " + filePath(d) + "\n";
            String snippet = extractAddedLines(d.getDiff(), perFile);
            if (snippet.isBlank()) snippet = safePrefix(d.getDiff(), perFile);

            int extra = header.length() + snippet.length() + 2;
            if (sb.length() + extra > maxQ) return sb.toString();

            sb.append(header).append(snippet).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Извлекает только добавленные строки из diff (строки начинающиеся с '+').
     *
     * @param diff     - текст diff
     * @param maxChars - максимум символов
     * @return добавленные строки или пустая строка если их нет
     */
    private String extractAddedLines(String diff, int maxChars) {
        if (diff == null || diff.isBlank() || maxChars <= 0) return "";

        StringBuilder sb = new StringBuilder(Math.min(maxChars, 1024));
        for (String line : diff.split("\n")) {
            if (sb.length() >= maxChars) return sb.toString();
            if (line.startsWith("+++")) continue;
            if (line.startsWith("+")) {
                String add = line + "\n";
                if (sb.length() + add.length() > maxChars) return sb.toString();
                sb.append(add);
            }
        }
        return sb.toString();
    }

    /**
     * Удаляет RAG_HEADER из результата для избежания дублирования
     */
    private String stripHeader(String rag) {
        if (rag == null || rag.isBlank()) return "";
        return rag.replace(RAG_HEADER, "");
    }

    /**
     * Разбивает RAG результат на блоки по эмодзи
     *
     * @param text - RAG результат от RagContextService
     * @return список блоков (каждый блок = один стандарт кодирования)
     */
    private List<String> splitBlocks(String text) {
        if (text == null || text.isBlank()) return List.of();

        String s = text.trim();
        int first = s.indexOf("📚 ");
        if (first < 0) return List.of(s);

        List<String> blocks = new ArrayList<>();
        int idx = first;

        while (idx >= 0) {
            int next = s.indexOf("📚 ", idx + 2);
            String block = (next >= 0) ? s.substring(idx, next) : s.substring(idx);
            block = block.trim();
            if (!block.isBlank()) blocks.add(block);
            idx = next;
        }

        return blocks;
    }

    /**
     * Выбирает блоки в пределах budget, удаляя дубли по hashCode
     *
     * @param blocks - список блоков
     * @param budget - бюджет символов
     * @param seen   - Set для дедупликации
     * @return отобранные блоки, объединённые в строку
     */
    private String joinBlocksWithBudget(List<String> blocks, int budget, Set<Integer> seen) {
        if (blocks == null || blocks.isEmpty() || budget <= 0) return "";

        StringBuilder sb = new StringBuilder(Math.min(budget, 2048));

        for (String block : blocks) {
            if (block != null && !block.isBlank()) {
                int hash = block.hashCode();
                if (!seen.contains(hash)) {
                    String add = block + "\n\n";
                    if (sb.length() + add.length() > budget) {
                        sb.append("...(RAG обрезан по лимиту)\n");
                        break;
                    }
                    sb.append(add);
                    seen.add(hash);
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Объединяет разделы general и perFile
     */
    private String joinSections(String general, String perFile) {
        return (general == null ? "" : general) + (perFile == null ? "" : perFile);
    }

    /**
     * Ограничивает результат по общему бюджету символов
     */
    private String limitTotal(String text, int totalBudget) {
        if (totalBudget > 0 && text.length() > totalBudget) {
            return text.substring(0, totalBudget) + "\n...(RAG контекст обрезан по общему лимиту)\n";
        }
        return text;
    }

    /**
     * Возвращает размер diff текста (для сортировки по величине файлов)
     */
    private static int diffSize(MergeRequestDiff d) {
        String s = d.getDiff();
        return s == null ? 0 : s.length();
    }

    /**
     * Получает путь файла (приоритет newPath, fallback oldPath)
     */
    private String filePath(MergeRequestDiff d) {
        String p = d.getNewPath() != null ? d.getNewPath() : d.getOldPath();
        return (p == null || p.isBlank()) ? "unknown" : p;
    }

    /**
     * Возвращает prefix строки (обрезает до maxChars)
     */
    private String safePrefix(String s, int maxChars) {
        if (s == null || s.isEmpty() || maxChars <= 0) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    /**
     * Возвращает значение если > 0, иначе default
     */
    private int positiveOrDefault(int value, int def) {
        return value > 0 ? value : def;
    }

    /**
     * Record для хранения бюджетов
     *
     * @param totalBudget   - общий бюджет RAG контекста
     * @param generalBudget - бюджет для общего раздела
     * @param perFileBudget - бюджет на каждый файл
     * @param topFiles      - количество top файлов для второго прохода
     */
    private record Budgets(int totalBudget, int generalBudget, int perFileBudget, int topFiles) {
    }
}
