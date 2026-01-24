package com.groviate.telegramcodereviewbot.client;

import com.groviate.telegramcodereviewbot.model.MergeRequestDiffRefs;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import io.github.resilience4j.retry.annotation.Retry;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
    @Retry(name = "gitlab")
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

    @Retry(name="gitlab")
    public List<MergeRequest> getOpenMergeRequestsUpdatedAfter(Integer projectId, Instant updatedAfter, int perPage) {
        log.info("Получаем opened MR для проекта:{} updatedAfter={}", projectId, updatedAfter);

        try {
            String updatedAfterIso = DateTimeFormatter.ISO_INSTANT.format(updatedAfter);

            String url = UriComponentsBuilder
                    .fromUriString(gitlabApiUrl)
                    .pathSegment("projects", String.valueOf(projectId), "merge_requests")
                    .queryParam("state", "opened")
                    .queryParam("order_by", "updated_at")
                    .queryParam("sort", "desc")
                    .queryParam("per_page", perPage)
                    .queryParam("updated_after", updatedAfterIso)
                    .build()
                    .toUriString();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> response = restTemplate.getForObject(url, List.class);

            if (response == null || response.isEmpty()) {
                return List.of();
            }

            return response.stream()
                    .map(map -> objectMapper.convertValue(map, MergeRequest.class))
                    .toList();

        } catch (Exception e) {
            log.error("Ошибка при получении списка MR (updated_after) для проекта {}: {}", projectId, e.getMessage());
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
    @Retry(name="gitlab")
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
    @Retry(name = "gitlab")
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
    @Retry(name = "gitlab")
    public void postComment(long projectId, long mergeRequestIid, String commentText) {
        log.info("Публикуем комментарий в MR {}/{}", projectId, mergeRequestIid);

        String url = String.format("%s/projects/%d/merge_requests/%d/notes",
                gitlabApiUrl, projectId, mergeRequestIid);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("body", commentText);

            restTemplate.postForEntity(url, requestBody, Void.class);

            log.info("Комментарий опубликован в MR {}/{}", projectId, mergeRequestIid);

        } catch (Exception e) {
            throw (e instanceof GitlabClientException ge)
                    ? ge
                    : new GitlabClientException("Не удалось опубликовать комментарий ...", e);
        }
    }

    public record LinePosition(String oldPath, String newPath, Integer oldLine, Integer newLine) { }

    /**
     * Публикует встроенный комментарий к строке кода
     */
    @Retry(name = "gitlab")
    public void postLineComment(Integer projectId,
                                Integer mergeRequestId,
                                MergeRequestDiffRefs refs,
                                LinePosition position,
                                String commentText) {

        if (refs == null) {
            throw new GitlabClientException("diff refs is null (cannot post inline comment)", null);
        }
        if (position == null) {
            throw new GitlabClientException("position is null (cannot post inline comment)", null);
        }
        if (position.newPath() == null || position.newPath().isBlank()) {
            throw new GitlabClientException("newPath is blank (cannot post inline comment)", null);
        }

        String oldPath = (position.oldPath() == null || position.oldPath().isBlank())
                ? position.newPath()
                : position.oldPath();

        Integer oldLine = position.oldLine();
        Integer newLine = position.newLine();

        boolean hasOld = oldLine != null && oldLine > 0;
        boolean hasNew = newLine != null && newLine > 0;

        if (!hasOld && !hasNew) {
            throw new GitlabClientException("Both oldLine and newLine are invalid (cannot post inline comment)", null);
        }

        String url = String.format("%s/projects/%d/merge_requests/%d/discussions",
                gitlabApiUrl, projectId, mergeRequestId);

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("body", commentText);

            form.add("position[base_sha]", refs.getBaseSha());
            form.add("position[start_sha]", refs.getStartSha());
            form.add("position[head_sha]", refs.getHeadSha());
            form.add("position[position_type]", "text");

            form.add("position[old_path]", oldPath);
            form.add("position[new_path]", position.newPath());

            if (hasOld) {
                form.add("position[old_line]", String.valueOf(oldLine));
            }
            if (hasNew) {
                form.add("position[new_line]", String.valueOf(newLine));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                String body = response.getBody();
                throw new GitlabClientException(
                        "Inline comment failed: " + response.getStatusCode()
                                + (body == null ? "" : (", body=" + body)),
                        null
                );
            }

        } catch (Exception e) {
            throw new GitlabClientException("Не удалось опубликовать inline comment: " + e.getMessage(), e);
        }
    }

    @Retry(name="gitlab")
    public MergeRequestDiffRefs getLatestDiffRefs(Integer projectId, Integer mergeRequestId) {
        String url = String.format("%s/projects/%d/merge_requests/%d/versions",
                gitlabApiUrl, projectId, mergeRequestId);

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> versions = restTemplate.getForObject(url, List.class);

            if (versions != null && !versions.isEmpty()) {
                Map<String, Object> latest = versions.getFirst();

                String base = (String) latest.get("base_commit_sha");
                String start = (String) latest.get("start_commit_sha");
                String head = (String) latest.get("head_commit_sha");

                if (base != null && start != null && head != null) {
                    return MergeRequestDiffRefs.builder()
                            .baseSha(base)
                            .startSha(start)
                            .headSha(head)
                            .build();
                }
            }

            MergeRequest mr = getMergeRequest(projectId, mergeRequestId);
            if (mr != null && mr.getDiffRefs() != null) {
                return mr.getDiffRefs();
            }

            throw new GitlabClientException("Не удалось получить diff refs (versions пуст / diff_refs пуст)", null);

        } catch (Exception e) {
            log.error("Ошибка получения MR: {}", e.getMessage());
            throw new GitlabClientException("Ошибка при поиске MR", e);
        }
    }
}
