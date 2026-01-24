package com.groviate.telegramcodereviewbot.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.exception.AiNonRetryableException;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Сервис для анализа кода через AI
 * <p>
 * Процесс работы:
 * 1. Получить MR и список измененных файлов
 * 2. Подготовить промпт для AI через PromptTemplateService
 * 3. Отправить промпт в ChatClient (Spring AI)
 * 4. ChatClient отправит промпт в OpenAI API
 * 5. GPT вернет ревью в JSON формате
 * 6. Парсим JSON в CodeReviewResult с помощью ObjectMapper
 * 7. Возвращаем результат
 */
@Service
@Slf4j
public class CodeReviewService {

    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final CodeReviewProperties codeReviewProperties;
    private final MergeRequestRagContextProvider ragContextProvider;
    private final AiChatGateway aiChatGateway;
    private final ReviewMetricsService metrics;

    private static final String EMPTY_METADATA = "files=0, added=0, removed=0, diffChars=0";

    public CodeReviewService(PromptTemplateService promptTemplateService,
                             ObjectMapper objectMapper,
                             CodeReviewProperties codeReviewProperties,
                             MergeRequestRagContextProvider ragContextProvider,
                             AiChatGateway aiChatGateway,
                             ReviewMetricsService metrics) {
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
        this.codeReviewProperties = codeReviewProperties;
        this.ragContextProvider = ragContextProvider;
        this.aiChatGateway = aiChatGateway;
        this.metrics = metrics;
        this.objectMapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Анализирует код в Merge Request через OpenAI
     *
     * @param mergeRequest - данные о MR (заголовок, описание, ID)
     * @param diffs        - список измённых файлов с кодом
     * @return результат анализа с оценкой и рекомендациями
     */
    @CircuitBreaker(name = "openai", fallbackMethod = "analyzeCodeFallback")
    public CodeReviewResult analyzeCode(MergeRequest mergeRequest, List<MergeRequestDiff> diffs) {

        if (diffs == null || diffs.isEmpty()) {
            log.info("Нет изменений в MR - ревью не требуется.");
            return createEmptyReview("Нет изменений для анализа.", List.of());
        }

        String title = mergeRequest != null ? mergeRequest.getTitle() : "No title";
        String description = mergeRequest != null ? mergeRequest.getDescription() : "Нет описания";

        String ragContext = "";

        if (!codeReviewProperties.isRagEnabled()) {
            // RAG выключен конфигом => это не ошибка, считаем как skipped
            metrics.markRagSkipped(null);
        } else {
            io.micrometer.core.instrument.Timer.Sample ragSample = metrics.startRag();
            try {
                ragContext = ragContextProvider.buildRagContext(diffs);

                if (ragContext == null || ragContext.isBlank()) {
                    metrics.markRagEmpty(ragSample);
                    log.debug("RAG включен, но контекст пустой (нет релевантных документов)");
                    ragContext = "";
                } else {
                    metrics.markRagSuccess(ragSample);
                }

            } catch (Exception e) {
                metrics.markRagFailed(ragSample);

                log.warn("RAG контекст недоступен, продолжаю без него. cause={}", safeMsg(e));
                log.debug("RAG failure details", e);

                ragContext = "";
            }
        }

        String userPrompt = promptTemplateService.preparePrompt(diffs, title, description, ragContext);
        String systemPrompt = promptTemplateService.getSystemPrompt();

        String aiResponse = aiChatGateway.ask(systemPrompt, userPrompt);

        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("AI вернул пустой ответ");
            return createEmptyReview("AI вернул пустой ответ.", diffs);
        }

        return parseAiResponse(aiResponse, diffs);
    }

    /**
     * Парсит JSON ответ от OpenAI в объект CodeReviewResult
     *
     * @param jsonResponse - JSON ответ от GPT
     * @return объект CodeReviewResult с оценкой и рекомендациями
     */
    @SuppressWarnings("u")
    private CodeReviewResult parseAiResponse(String jsonResponse, List<MergeRequestDiff> diffs) {
        try {
            String normalized = extractJsonObject(jsonResponse);
            if (normalized == null || normalized.isBlank()) {
                return createEmptyReview("AI вернул пустой/некорректный JSON", diffs);
            }

            CodeReviewResult result = objectMapper.readValue(normalized, CodeReviewResult.class);

            if (result.getSuggestions() == null) {
                result.setSuggestions(List.of());
            }

            // Если AI не прислал metadata — добавим наше
            if (result.getMetadata() == null || result.getMetadata().isBlank()) {
                result.setMetadata(buildMetadata(diffs));
            }

            return result;

        } catch (Exception e) {
            log.error("Ошибка при парсинге ответа AI: {}", e.getMessage(), e);
            return createEmptyReview("Ошибка при анализе ответа AI (парсинг JSON)", diffs);
        }
    }

    /**
     * Создаёт пустой результат ревью
     *
     */
    private CodeReviewResult createEmptyReview(String summary, List<MergeRequestDiff> diffs) {
        return CodeReviewResult.builder()
                .score(0)
                .summary(summary)
                .suggestions(List.of())
                .metadata(buildMetadata(diffs))
                .build();
    }

    /**
     * Формирует метаданные по MR: количество файлов, добавленные/удалённые строки и размер diff.
     * <p>
     * Также добавляет "сэмпл" путей файлов (до 10 штук) для удобства диагностики.
     *
     * @param diffs список изменённых файлов
     * @return строка с метаданными в человекочитаемом формате
     */
    private String buildMetadata(List<MergeRequestDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return EMPTY_METADATA;
        }

        MetadataStats stats = computeMetadataStats(diffs);
        String sampleFiles = buildSampleFiles(diffs);

        return formatMetadata(stats, sampleFiles);
    }

    /**
     * Считает агрегированную статистику по diffs: количество файлов, diffChars, added/removed.
     *
     * @param diffs список изменённых файлов
     * @return агрегированная статистика по изменениям
     */
    private MetadataStats computeMetadataStats(List<MergeRequestDiff> diffs) {
        MetadataStats stats = new MetadataStats(diffs.size());

        for (MergeRequestDiff d : diffs) {
            String text = d.getDiff();
            if (text == null) {
                continue;
            }

            stats.diffChars += text.length();
            countAddedRemoved(text, stats);
        }

        return stats;
    }

    /**
     * Считает количество добавленных/удалённых строк в unified diff.
     * <p>
     * Важно: строки заголовков diff (например, "+++" / "---") не учитываются.
     *
     * @param diffText текст diff для одного файла
     * @param stats    объект статистики, который будет обновлён
     */
    private void countAddedRemoved(String diffText, MetadataStats stats) {
        for (String line : diffText.split("\n")) {
            if (isAddedLine(line)) {
                stats.added++;
            } else if (isRemovedLine(line)) {
                stats.removed++;
            }
        }
    }

    /**
     * Проверяет, является ли строка diff "добавленной" (начинается с '+'),
     * исключая служебные строки заголовка ("+++").
     *
     * @param line строка diff
     * @return true если это добавленная строка кода
     */
    private boolean isAddedLine(String line) {
        return line.startsWith("+") && !line.startsWith("+++");
    }

    /**
     * Проверяет, является ли строка diff "удалённой" (начинается с '-'),
     * исключая служебные строки заголовка ("---").
     *
     * @param line строка diff
     * @return true если это удалённая строка кода
     */
    private boolean isRemovedLine(String line) {
        return line.startsWith("-") && !line.startsWith("---");
    }

    /**
     * Формирует строку с примерами путей изменённых файлов (до 10 штук).
     * <p>
     * Для каждого diff выбирает newPath, а если он отсутствует — oldPath.
     *
     * @param diffs список изменённых файлов
     * @return строка с путями файлов, разделёнными запятыми, или пустая строка
     */
    private String buildSampleFiles(List<MergeRequestDiff> diffs) {
        return diffs.stream()
                .map(d -> d.getNewPath() != null ? d.getNewPath() : d.getOldPath())
                .filter(Objects::nonNull)
                .limit(10)
                .collect(Collectors.joining(", "));
    }

    /**
     * Приводит статистику и sampleFiles к итоговой строке metadata.
     *
     * @param stats       рассчитанная статистика по diffs
     * @param sampleFiles примеры файлов (может быть пустой строкой)
     * @return итоговая строка metadata
     */
    private String formatMetadata(MetadataStats stats, String sampleFiles) {
        String base = "files=" + stats.files +
                ", added=" + stats.added +
                ", removed=" + stats.removed +
                ", diffChars=" + stats.diffChars;

        return sampleFiles.isBlank() ? base : base + "\nfiles_sample=" + sampleFiles;
    }

    /**
     * Внутренняя DTO-структура для накопления статистики по MR.
     */
    private static final class MetadataStats {
        private final int files;
        private long diffChars;
        private int added;
        private int removed;

        private MetadataStats(int files) {
            this.files = files;
        }
    }


    /**
     * Извлекает JSON-объект из произвольного текста.
     * <p>
     * Нужен для случаев, когда AI возвращает JSON, обёрнутый в дополнительный текст.
     *
     * @param raw исходная строка ответа AI
     * @return JSON-объект в виде строки или null, если объект не найден
     */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        int start = s.indexOf('{');
        if (start < 0) return null;

        int end = findJsonEnd(s, start);
        if (end < 0) return null;

        return s.substring(start, end + 1);
    }

    /**
     * Вспомогательный метод для поиска конца JSON объекта.
     */
    private int findJsonEnd(String s, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            // Запоминаем, был ли текущий символ экранирован (предыдущим '\').
            boolean currentCharIsEscaped = escaped;

            // Обновляем флаг экранирования для СЛЕДУЮЩЕГО символа
            escaped = updateEscapedState(c, escaped);

            // Если текущий символ НЕ был экранирован — можно обрабатывать кавычки/скобки
            if (!currentCharIsEscaped) {
                // Кавычка переключает режим строки только если НЕ экранирована
                if (c == '"') {
                    inString = !inString;
                } else if (!inString) {
                    // Скобки считаем только вне строк
                    depth = updateDepth(depth, c);
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Управляет флагом экранирования символов.
     */
    private boolean updateEscapedState(char c, boolean wasEscaped) {
        // Если предыдущий символ был \, текущий был экранирован
        if (wasEscaped) {
            return false;
        }
        // Если текущий символ \, он экранирует СЛЕДУЮЩИЙ символ
        return c == '\\';
    }

    /**
     * Обновление глубины вложенности скобок.
     */
    private int updateDepth(int depth, char c) {
        if (c == '{') {
            return depth + 1;
        }
        if (c == '}') {
            return depth - 1;
        }
        return depth;
    }

    /**
     * Fallback для {@link #analyzeCode(MergeRequest, List)} при недоступности OpenAI
     * или когда CircuitBreaker не разрешает вызовы.
     * <p>
     * Для {@link AiNonRetryableException} fallback не применяется — исключение пробрасывается,
     * чтобы не маскировать конфигурационные/валидационные ошибки.
     *
     * @param mergeRequest данные MR
     * @param diffs        изменённые файлы
     * @param t            причина fallback (ошибка вызова или CallNotPermittedException и т.п.)
     * @return "пустой" результат ревью с пометкой openai_fallback=true
     * @throws AiNonRetryableException если исходная ошибка относится к non-retryable
     */
    @SuppressWarnings("unused")
    private CodeReviewResult analyzeCodeFallback(MergeRequest mergeRequest,
                                                 List<MergeRequestDiff> diffs,
                                                 Throwable t) {
        if (t instanceof AiNonRetryableException nonRetryable) {
            // Не маскируем конфигурационные/валидационные ошибки fallback'ом
            throw nonRetryable;
        }

        log.error("OpenAI недоступен или circuit breaker открыт. Возвращаю fallback.", t);

        String summary = "OpenAI сейчас недоступен. Ревью не выполнено, попробуйте позже.";
        CodeReviewResult result = createEmptyReview(summary, diffs);

        String meta = result.getMetadata();
        result.setMetadata(meta + "\nopenai_fallback=true\nerror=" + safeMsg(t));
        return result;
    }

    /**
     * Безопасно формирует короткое сообщение об ошибке для metadata:
     *
     * @param t throwable (может быть null)
     * @return строка сообщения (или имя класса), ограниченная по длине
     */
    private String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return (m.length() > 300) ? m.substring(0, 300) : m;
    }
}
