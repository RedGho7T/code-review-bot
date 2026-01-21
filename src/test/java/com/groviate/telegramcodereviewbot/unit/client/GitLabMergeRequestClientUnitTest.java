package com.groviate.telegramcodereviewbot.unit.client;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.exception.GitlabClientException;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Юнит тесты GitLabMergeRequestClient")
class GitLabMergeRequestClientUnitTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private GitLabMergeRequestClient client;

    private String gitlabUrl;
    private Integer projectId;
    private Integer mergeRequestId;

    @BeforeEach
    void setUp() {
        this.gitlabUrl = resolveGitlabApiUrl();
        this.projectId = 24;
        this.mergeRequestId = 1;

        ReflectionTestUtils.setField(client, "gitlabApiUrl", gitlabUrl);
    }

    private static String resolveGitlabApiUrl() {
        String fromSysProp = System.getProperty("gitlab.api.url");
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return stripTrailingSlash(fromSysProp);
        }

        String fromEnv = System.getenv("GITLAB_API_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return stripTrailingSlash(fromEnv);
        }

        // безопасный дефолт для юнитов
        return "http://gitlab.test/api/v4";
    }

    private static String stripTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    private Map<String, Object> createMockMrMap(Integer id, String title) {
        Map<String, Object> mrMap = new LinkedHashMap<>();
        mrMap.put("id", id);
        mrMap.put("iid", id);
        mrMap.put("title", title);
        mrMap.put("state", "opened");
        mrMap.put("source_branch", "feature/test");
        mrMap.put("target_branch", "develop");
        mrMap.put("merge_status", "can_be_merged");
        mrMap.put("has_conflicts", false);
        return mrMap;
    }

    @Nested
    @DisplayName("Тесты для метода getMergeRequestsByProjectId()")
    class GetMergeRequestsByProjectIdTests {

        /**
         * Given: Валидный ID проекта и два открытых MR
         * When: Вызывается getMergeRequestsByProjectId()
         * Then: Возвращается список из двух MR со статусом "opened"
         */
        @Test
        @DisplayName("Успешно получить список открытых MR проекта")
        void givenValidProjectIdWithMrsWhenGetMergeRequestsThenReturnList() {
            List<Map<String, Object>> mockResponse = List.of(
                    createMockMrMap(1, "Fix login bug"),
                    createMockMrMap(2, "Add new tests")
            );

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened",
                    List.class
            )).thenReturn(mockResponse);

            List<MergeRequest> result = client.getMergeRequestsByProjectId(projectId);

            assertThat(result)
                    .isNotNull()
                    .hasSize(2)
                    .allMatch(mr -> mr.getStatus().equals("opened"));
            assertThat(result)
                    .extracting(MergeRequest::getId, MergeRequest::getTitle)
                    .containsExactly(
                            tuple(1, "Fix login bug"),
                            tuple(2, "Add new tests")
                    );
        }

        /**
         * Given: Проект существует, но в нём нет открытых MR
         * When: Вызывается getMergeRequestsByProjectId()
         * Then: Возвращается пустой список
         */
        @Test
        @DisplayName("Вернуть пустой список, если в проекте нету открытых MR")
        void givenEmptyListWhenGetMergeRequestsThenReturnEmptyList() {
            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened",
                    List.class
            )).thenReturn(List.of());

            List<MergeRequest> result = client.getMergeRequestsByProjectId(projectId);

            assertThat(result)
                    .isNotNull()
                    .isEmpty();
        }

        /**
         * Given: API возвращает null вместо массива
         * When: Вызывается getMergeRequestsByProjectId()
         * Then: Возвращается пустой список (graceful handling)
         */
        @Test
        @DisplayName("Если API вернет Null метод не упадет и вернет пустой список")
        void givenNullResponseWhenGetMergeRequestsThenReturnEmptyList() {
            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened",
                    List.class
            )).thenReturn(null);

            List<MergeRequest> result = client.getMergeRequestsByProjectId(projectId);

            assertThat(result)
                    .isNotNull()
                    .isEmpty();
        }

        /**
         * Given: В проекте только один MR
         * When: Вызывается getMergeRequestsByProjectId()
         * Then: Возвращается список с одним элементом
         */
        @Test
        @DisplayName("Должен вернуть список с одним MR когда API возвращает один элемент")
        void givenSingleMrWhenGetMergeRequestsThenReturnSingleElementList() {
            List<Map<String, Object>> mockResponse = List.of(
                    createMockMrMap(1, "Single fix")
            );

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened",
                    List.class
            )).thenReturn(mockResponse);

            List<MergeRequest> result = client.getMergeRequestsByProjectId(projectId);

            assertThat(result)
                    .hasSize(1)
                    .first()
                    .extracting(MergeRequest::getId)
                    .isEqualTo(1);
        }

        /**
         * Given: API возвращает полностью заполненный MR со всеми полями
         * When: Вызывается getMergeRequestsByProjectId()
         * Then: Все поля правильно маппируются в объект MergeRequest
         */
        @Test
        @DisplayName("Должен вернуть MR со всеми полями")
        void givenFullyPopulatedMrWhenGetMergeRequestsThenAllFieldsSet() {
            Map<String, Object> mrMap = new HashMap<>();
            mrMap.put("id", 1);
            mrMap.put("iid", 1);
            mrMap.put("title", "Fix authentication");
            mrMap.put("state", "opened");
            mrMap.put("source_branch", "feature/auth");
            mrMap.put("target_branch", "develop");
            mrMap.put("merge_status", "can_be_merged");
            mrMap.put("has_conflicts", false);

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests?state=opened",
                    List.class
            )).thenReturn(List.of(mrMap));

            List<MergeRequest> result = client.getMergeRequestsByProjectId(projectId);

            MergeRequest mr = result.getFirst();
            assertThat(mr)
                    .hasFieldOrPropertyWithValue("id", 1)
                    .hasFieldOrPropertyWithValue("title", "Fix authentication")
                    .hasFieldOrPropertyWithValue("status", "opened")
                    .hasFieldOrPropertyWithValue("sourceBranch", "feature/auth")
                    .hasFieldOrPropertyWithValue("targetBranch", "develop");
        }
    }

    @Nested
    @DisplayName("Тесты для метода getMergeRequest()")
    class GetMergeRequestTests {

        /**
         * Given: Валидный projectId и mergeRequestId с существующим MR
         * When: Вызывается getMergeRequest(projectId, mergeRequestId)
         * Then: Возвращается объект MergeRequest с полной информацией
         */
        @Test
        @DisplayName("Должен вернуть детали MR, когда переданы валидные ID проектов")
        void givenValidIdsWhenGetMergeRequestThenReturnMrDetails() {
            MergeRequest mockMr = MergeRequest.builder()
                    .id(mergeRequestId)
                    .iid(mergeRequestId)
                    .title("Fix login bug")
                    .status("opened")
                    .sourceBranch("feature/auth")
                    .targetBranch("develop")
                    .mergeStatus("can_be_merged")
                    .hasConflicts(false)
                    .build();

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId,
                    MergeRequest.class
            )).thenReturn(mockMr);

            MergeRequest result = client.getMergeRequest(projectId, mergeRequestId);

            assertThat(result)
                    .isNotNull()
                    .hasFieldOrPropertyWithValue("id", mergeRequestId)
                    .hasFieldOrPropertyWithValue("title", "Fix login bug")
                    .hasFieldOrPropertyWithValue("status", "opened");
        }

        /**
         * Given: MergeRequest с указанными ID не существует в проекте
         * When: Вызывается getMergeRequest(projectId, invalidId)
         * Then: Возвращается null
         */
        @Test
        @DisplayName("Должен вернуть NULL если ID (merge request) не существует")
        void givenNonExistentMrWhenGetMergeRequestThenReturnNull() {
            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId,
                    MergeRequest.class
            )).thenReturn(null);

            MergeRequest result = client.getMergeRequest(projectId, mergeRequestId);

            assertThat(result).isNull();
        }

        /**
         * Given: API сервер недоступен и выбрасывает исключение
         * When: Вызывается getMergeRequest(projectId, mergeRequestId)
         * Then: Выбрасывается GitlabClientException
         */
        @Test
        @DisplayName("Должен выбросить GitlabClientException при ошибке вызова API")
        void givenApiErrorWhenGetMergeRequestThenThrowException() {
            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId,
                    MergeRequest.class
            )).thenThrow(new RuntimeException("Api error"));

            assertThatThrownBy(() -> client.getMergeRequest(projectId, mergeRequestId))
                    .isInstanceOf(GitlabClientException.class);
        }

        /**
         * Given: MR с конфликтами слияния
         * When: Вызывается getMergeRequest(projectId, mergeRequestId)
         * Then: Возвращается MR с hasConflicts=true и mergeStatus="cannot_be_merged"
         */
        @Test
        @DisplayName("Должен вернуть MR с информацией о конфликтах")
        void givenMrWithConflictsWhenGetMergeRequestThenReturnConflictedMr() {
            MergeRequest mockMr = MergeRequest.builder()
                    .id(mergeRequestId)
                    .title("Conflicted MR")
                    .status("opened")
                    .sourceBranch("feature/conflict")
                    .targetBranch("main")
                    .hasConflicts(true)
                    .mergeStatus("cannot_be_merged")
                    .build();

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId,
                    MergeRequest.class
            )).thenReturn(mockMr);

            MergeRequest result = client.getMergeRequest(projectId, mergeRequestId);

            assertThat(result)
                    .isNotNull()
                    .hasFieldOrPropertyWithValue("hasConflicts", true)
                    .hasFieldOrPropertyWithValue("mergeStatus", "cannot_be_merged");
        }
    }

    @Nested
    @DisplayName("Тесты для метода getChanges()")
    class GetChangesTests {

        @Test
        @DisplayName("Должен вернуть список изменений файлов для MR")
        void givenValidIdsWhenGetChangesThenReturnDiffList() {
            Map<String, Object> change1 = new LinkedHashMap<>();
            change1.put("old_path", "src/main/java/File1.java");
            change1.put("new_path", "src/main/java/File1.java");
            change1.put("new_file", false);

            Map<String, Object> change2 = new LinkedHashMap<>();
            change2.put("old_path", "src/test/java/NewTest.java");
            change2.put("new_path", "src/test/java/NewTest.java");
            change2.put("new_file", true);

            Map<String, Object> responseWrapper = new LinkedHashMap<>();
            responseWrapper.put("changes", List.of(change1, change2));

            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId + "/changes",
                    Map.class
            )).thenReturn(responseWrapper);

            List<MergeRequestDiff> result = client.getChanges(projectId, mergeRequestId);

            assertThat(result)
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(2)
                    .extracting(MergeRequestDiff::getOldPath)
                    .containsExactly(
                            "src/main/java/File1.java",
                            "src/test/java/NewTest.java"
                    );
        }

        @Test
        @DisplayName("Должен вернуть пустой список если MR не содержит изменений")
        void givenMrWithoutChangesWhenGetChangesThenReturnEmptyList() {
            when(restTemplate.getForObject(
                    gitlabUrl + "/projects/" + projectId + "/merge_requests/" + mergeRequestId + "/changes",
                    Map.class
            )).thenReturn(null);

            List<MergeRequestDiff> result = client.getChanges(projectId, mergeRequestId);

            assertThat(result)
                    .isNotNull()
                    .isEmpty();
        }
    }
}
