package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.exception.RagContextException;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Провайдер RAG контекста в полном режиме
 * <p>
 * Активируется при rag-economy-mode=false в конфигурации.
 * <p>
 * Обрабатывает ВСЕ файлы в MR:
 * <ol>
 *   <li>Для каждого файла запрашивает RAG контекст отдельно через RagContextService</li>
 *   <li>Форматирует результат с заголовком "--- Стандарты для файла: {path} ---"</li>
 *   <li>Объединяет все блоки в один RAG контекст для промпта</li>
 * </ol>
 * <p>
 * Отличие от EconomyMergeRequestRagContextProvider:
 * - Full: N запросов embedding (по одному на файл), больше токенов OpenAI, больше релевантных результатов
 * - Economy: 1-2 запроса embedding (общий + top файлы), меньше токенов, соблюдение строгих лимитов
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "code-review", name = "rag-economy-mode", havingValue = "false")
public class FullMergeRequestRagContextProvider implements MergeRequestRagContextProvider {

    private final RagContextService ragContextService;

    public FullMergeRequestRagContextProvider(RagContextService ragContextService) {
        this.ragContextService = ragContextService;
    }

    /**
     * Собирает RAG контекст для всех файлов в Merge Request
     * <p>
     * Процесс:
     * <ol>
     *   <li>Фильтрует кандидаты: не deleted файлы с непустым diff</li>
     *   <li>Для каждого файла: запрашивает RAG через ragContextService.getContextForCode(diff)</li>
     *   <li>Форматирует каждый результат с заголовком файла</li>
     *   <li>Объединяет все блоки в один строку</li>
     * </ol>
     *
     * @param diffs - список MergeRequestDiff с информацией о файлах и изменениях
     * @return строка с RAG контекстом для всех файлов, готовая добавить в промпт.
     */
    @Override
    public String buildRagContext(List<MergeRequestDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return "";
        }

        String result = diffs.stream()
                .filter(this::isCandidate)
                .map(this::toRagBlock)
                .filter(this::isNotBlank)
                .collect(Collectors.joining())
                .trim();

        if (result.isBlank()) {
            log.debug("FULL: RAG контекст не найден");
            return "";
        }

        log.info("FULL: RAG контекст собран, chars={}", result.length());
        return result;
    }

    /**
     * Проверяет что файл кандидат для RAG
     * <p>
     * Условия: не null, не deleted файл, имеет непустой diff.
     *
     * @param diff - MergeRequestDiff для проверки
     * @return true если файл подходит для запроса RAG контекста
     */
    private boolean isCandidate(MergeRequestDiff diff) {
        return diff != null
                && !diff.isDeletedFile()
                && isNotBlank(diff.getDiff());
    }

    /**
     * Преобразует MergeRequestDiff в блок RAG контекста с заголовком
     * <p>
     * Процесс:
     * <ol>
     *   <li>Запрашивает RAG через ragContextService.getContextForCode(diff.getDiff())</li>
     *   <li>Если RAG пустой - возвращает пустую строку</li>
     *   <li>Иначе форматирует с заголовком "--- Стандарты для файла: {path} ---"</li>
     * </ol>
     *
     * @param diff - MergeRequestDiff
     * @return форматированный блок RAG или пустая строка если RAG не найден
     */
    private String toRagBlock(MergeRequestDiff diff) {
        final String fileRag;
        try {
            fileRag = ragContextService.getContextForCode(diff.getDiff());
        } catch (RagContextException e) {
            log.warn("RAG failed for file {}, continue without RAG: {}", resolveFilePath(diff), e.getMessage());
            return "";
        }

        if (!isNotBlank(fileRag)) {
            return "";
        }

        String filePath = resolveFilePath(diff);
        return "\n--- Стандарты для файла: " + filePath + " ---" + fileRag;
    }

    /**
     * Получает путь файла из diff (приоритет newPath, fallback oldPath)
     *
     * @param diff - MergeRequestDiff
     * @return new_path или old_path или пустая строка
     */
    private String resolveFilePath(MergeRequestDiff diff) {
        if (diff.getNewPath() != null) return diff.getNewPath();
        if (diff.getOldPath() != null) return diff.getOldPath();
        return "<unknown>";
    }

    /**
     * Проверяет что строка не пустая
     */
    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
