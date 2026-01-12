package com.groviate.telegramcodereviewbot.client;

import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для коммуникации с Gitlab API
 * <p>
 * GitLab API endpoints:
 * - GET /projects/{id}/merge_requests?state=opened получение списка всех открытых MR проекта
 * - GET /projects/{id}/merge_requests/{iid} получение деталей конкретного MR в проекте
 * - GET /projects/{id}/merge_requests/{iid}/changes получаем список измененных файлов для MR проекта
 */
@Component
@Slf4j
public class GitLabMergeRequestClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String gitlabApiUrl;

    public GitLabMergeRequestClient(
            RestTemplate restTemplate,
            @Value("${gitlab.api.url}") String gitlabApiUrl
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
        this.gitlabApiUrl = gitlabApiUrl;
    }

    /**
     * Получает список всех открытых Merge Requests для конкретного проекта
     *
     * @param projectId проекта в Gitlab
     * @return Immutable {@link List} открытых MR для проекта, если null -> пустой список
     * @throws GitlabClientException если произойдёт ошибка при обращении к API / парсинге
     */
    public List<MergeRequest> getMergeRequestsByProjectId(Integer projectId) {

        log.info("Получаем список всех открытых MR для проекта:{}", projectId);

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> response = restTemplate.getForObject(
                    String.format(
                            "%s/projects/%d/merge_requests?state=opened",
                            gitlabApiUrl,
                            projectId
                    ),
                    List.class
            );
            if (response == null || response.isEmpty()) {
                log.info("Для проекта {} нет открытых MR", projectId);
                return List.of();
            }
            return response.stream()
                    .map(map -> objectMapper.convertValue(map, MergeRequest.class))
                    .toList();
        } catch (Exception e) {
            log.error("Ошибка при получении списка MR для проекта {}: {}", projectId, e.getMessage());
            throw new GitlabClientException("Ошибка при получении списка MR", e);
        }
    }

    /**
     * Получает детальную информацию об одном конкретном Merge Request
     *
     * @param projectId      - ID проекта в Gitlab
     * @param mergeRequestId -ID(iid) конкретного MR в проекте
     * @return {@link MergeRequest} - полная информация о MR или NULL если не найден
     * @throws GitlabClientException если произойдёт ошибка при обращении к API / парсинге
     */
    public MergeRequest getMergeRequest(Integer projectId, Integer mergeRequestId) {

        log.info("Поиск MR в проекте:{} с номером:{}", projectId, mergeRequestId);

        try {
            MergeRequest mr = restTemplate.getForObject(
                    String.format(
                            "%s/projects/%d/merge_requests/%d",
                            gitlabApiUrl,
                            projectId,
                            mergeRequestId
                    ),
                    MergeRequest.class
            );

            if (mr == null) {
                log.warn("MR:'{}' не найден в проекте: '{}'", mergeRequestId, projectId);
                return null;
            }

            log.info("MR:'{}' со статусом '{}' получен", mr.getTitle(), mr.getStatus());
            return mr;

        } catch (Exception e) {
            log.error("Ошибка при получении MR: {}", e.getMessage());
            throw new GitlabClientException("Ошибка при поиске MR", e);
        }
    }

    /**
     * Получает список изменённых файлов и саму информацию об изменениях (diff)
     *
     * @param projectId      -  ID конкретного проекта в GitLab
     * @param mergeRequestId - IID конкретного Merge request проекта
     * @return Immutable {@link List} {@link MergeRequestDiff} с информацией о каждом изменённом файле
     * @throws GitlabClientException если произойдёт ошибка при обращении к API / парсинге
     */
    public List<MergeRequestDiff> getChanges(Integer projectId, Integer mergeRequestId) {

        log.info("Поиск изменений MR проекта:{} с номером:{}", projectId, mergeRequestId);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(
                    String.format(
                            "%s/projects/%d/merge_requests/%d/changes",
                            gitlabApiUrl,
                            projectId,
                            mergeRequestId
                    ),
                    Map.class
            );

            if (response == null) {
                log.warn("Не найдено изменений в MR проекта:{} c номером {}", projectId, mergeRequestId);
                return List.of();
            }

            @SuppressWarnings("unchecked")
            List<LinkedHashMap<String, Object>> changes =
                    (List<LinkedHashMap<String, Object>>) response.get("changes");

            if (changes == null || changes.isEmpty()) {
                log.info("MR не содержит измененных файлов");
                return List.of();
            }

            log.info("Получены изменения: {} файлов изменено", changes.size());
            return convertToMergeRequestDiffList(changes);

        } catch (Exception e) {
            log.error("Ошибка поиска изменений в MR: {}", e.getMessage());
            throw new GitlabClientException("Ошибка при поиске изменений", e);
        }
    }

    /**
     * Конвертирует List<LinkedHashMap> в List<MergeRequestDiff>
     *
     * @param changesRaw - список LinkedHashMap'ов из JSON ответа API Gitlab
     * @return {@link List} конвертированных объектов {@link MergeRequestDiff}
     */
    private List<MergeRequestDiff> convertToMergeRequestDiffList(List<LinkedHashMap<String, Object>> changesRaw) {

        return changesRaw.stream()
                .map(this::convertMapToMergeRequestDiff)
                .toList();
    }

    /**
     * Конвертирует один LinkedHashMap в MergeRequestDiff
     * Использует ObjectMapper для безопасной конвертации с обработкой @JsonProperty
     *
     * @param changeMap LinkedHashMap из JSON ответа API
     * @return преобразованный объект {@link MergeRequestDiff}
     * @throws GitlabClientException если преобразование не удалось
     */
    private MergeRequestDiff convertMapToMergeRequestDiff(LinkedHashMap<String, Object> changeMap) {

        try {
            return objectMapper.convertValue(changeMap, MergeRequestDiff.class);

        } catch (Exception e) {
            log.error("Ошибка при конвертации Map в MergeRequestDiff: {}", e.getMessage());
            throw new GitlabClientException("Ошибка при парсинге данных об изменениях в MR", e);
        }
    }

    /**
     * Публикует комментарий к Merge Request
     */
    public void postComment(long projectId, long mergeRequestIid, String commentText) {
        log.info("Публикуем комментарий в MR {}/{}", projectId, mergeRequestIid);

        String url = String.format("%s/projects/%d/merge_requests/%d/notes",
                gitlabApiUrl, projectId, mergeRequestIid);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("body", commentText);

            restTemplate.postForEntity(url, requestBody, Void.class);

            log.info("Комментарий опубликован в MR {}/{}", projectId, mergeRequestIid);

        } catch (GitlabClientException e) {
            log.error("GitlabClientException при публикации комментария: {}", e.getMessage(), e);
            throw new GitlabClientException(
                    "Не удалось опубликовать комментарий для MR " + projectId + "/" + mergeRequestIid + ": " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * Публикует встроенный комментарий к строке кода
     */
    public void postLineComment(Integer projectId, Integer mergeRequestId, Integer diffId,
                                Integer lineNumber, String commentText) {
        log.info("Публикуем встроенный комментарий на строке {} в MR {}/{}/diff/{}",
                lineNumber, projectId, mergeRequestId, diffId);

        try {
            String url = String.format("%s/projects/%d/merge_requests/%d/discussions",
                    gitlabApiUrl, projectId, mergeRequestId);

            // Подготавливаем тело запроса
            Map<String, Object> position = new HashMap<>();
            position.put("position_type", "text");
            position.put("new_line", lineNumber);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("body", commentText);
            requestBody.put("position", position);

            // ← КЛЮЧЕВОЕ: просто отправляем Map без headers!
            ResponseEntity<Void> response = restTemplate.postForEntity(
                    url,
                    requestBody,
                    Void.class
            );

            // Проверяем статус ответа
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Встроенный комментарий успешно опубликован в MR {}/{}, строка {}",
                        projectId, mergeRequestId, lineNumber);
            } else {
                log.error("Ошибка при публикации встроенного комментария, статус: {}",
                        response.getStatusCode());
                throw new GitlabClientException(
                        String.format("Ошибка публикации встроенного комментария: %s",
                                response.getStatusCode()),
                        null
                );
            }

        } catch (Exception e) {
            log.error("Ошибка при публикации встроенного комментария: {}", e.getMessage());
            throw new GitlabClientException(
                    String.format("Не удалось опубликовать встроенный комментарий в MR %d/%d: %s",
                            projectId, mergeRequestId, e.getMessage()),
                    e
            );
        }
    }

    /**
     * Создаёт URL для GitLab API
     */
    private String createUrl(Integer projectId, Integer mergeRequestId, String endpoint) {
        return String.format("%s/projects/%d/merge_requests/%d/%s",
                gitlabApiUrl,
                projectId,
                mergeRequestId,
                endpoint);
    }
}
