package com.groviate.telegramcodereviewbot.integration.client;

import com.groviate.telegramcodereviewbot.client.GitLabMergeRequestClient;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@DisplayName("GitLabMergeRequestClient Integration Tests")
class GitLabMergeRequestClientIntegrationTest {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private GitLabMergeRequestClient client;

    private MockRestServiceServer mockServer;

    private static final Integer PROJECT_ID = 24;
    private static final Integer MERGE_REQUEST_ID = 1;

    @Value("${gitlab.api.url}")
    private String gitlabApiUrl;

    private String baseUrl() {
        return gitlabApiUrl.replaceAll("/+$", "");
    }

    @BeforeEach
    void setUp() {
        this.mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Nested
    @DisplayName("getMergeRequestsByProjectId")
    class GetMergeRequestsByProjectIdIntegrationTests {

        @Test
        @DisplayName("Должен вернуть открытые MR")
        void givenGitlabApiReturnsOpenMrsWhenGetMergeRequestsThenParseSuccessfully() {
            String jsonResponse = """
                [
                    {
                        "id": 1,
                        "iid": 1,
                        "title": "Fix login bug",
                        "state": "opened",
                        "source_branch": "feature/auth",
                        "target_branch": "develop",
                        "merge_status": "can_be_merged",
                        "has_conflicts": false
                    },
                    {
                        "id": 2,
                        "iid": 2,
                        "title": "Add new feature",
                        "state": "opened",
                        "source_branch": "feature/new",
                        "target_branch": "develop",
                        "merge_status": "can_be_merged",
                        "has_conflicts": false
                    }
                ]
                """;

            mockServer.expect(requestTo(baseUrl() +
                            "/projects/" + PROJECT_ID + "/merge_requests?state=opened"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            List<MergeRequest> result = client.getMergeRequestsByProjectId(PROJECT_ID);

            mockServer.verify();

            assertThat(result)
                    .isNotNull()
                    .hasSize(2)
                    .extracting(MergeRequest::getId, MergeRequest::getTitle)
                    .containsExactly(
                            tuple(1, "Fix login bug"),
                            tuple(2, "Add new feature")
                    );
        }

        @Test
        @DisplayName("Должен вернуть пустой список если нет открытых MR")
        void givenGitlabApiReturnsEmptyArrayWhenGetMergeRequestsThenReturnEmptyList() {
            String jsonResponse = "[]";

            mockServer.expect(requestTo(baseUrl() +
                            "/projects/" + PROJECT_ID + "/merge_requests?state=opened"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            List<MergeRequest> result = client.getMergeRequestsByProjectId(PROJECT_ID);

            mockServer.verify();

            assertThat(result)
                    .isNotNull()
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("getMergeRequest")
    class GetMergeRequestIntegrationTests {

        @Test
        @DisplayName("Должен вернуть деталь конкретного MR")
        void givenGitlabApiReturnsMrDetailsWhenGetMergeRequestThenParseSuccessfully() {
            String jsonResponse = """
                {
                    "id": 1,
                    "iid": 1,
                    "title": "Fix authentication flow",
                    "state": "opened",
                    "source_branch": "feature/auth",
                    "target_branch": "develop",
                    "merge_status": "can_be_merged",
                    "has_conflicts": false
                }
                """;

            mockServer.expect(requestTo(baseUrl() + "/projects/" +
                            PROJECT_ID + "/merge_requests/" + MERGE_REQUEST_ID))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            MergeRequest result = client.getMergeRequest(PROJECT_ID, MERGE_REQUEST_ID);

            mockServer.verify();

            assertThat(result)
                    .isNotNull()
                    .hasFieldOrPropertyWithValue("id", 1)
                    .hasFieldOrPropertyWithValue("title", "Fix authentication flow")
                    .hasFieldOrPropertyWithValue("status", "opened")
                    .hasFieldOrPropertyWithValue("hasConflicts", false);
        }

        @Test
        @DisplayName("Должен парсить MR с конфликтами")
        void givenGitlabApiReturnsMrWithConflictsWhenGetMergeRequestThenParseMergeStatus() {
            String jsonResponse = """
                {
                    "id": 1,
                    "iid": 1,
                    "title": "Conflicted MR",
                    "state": "opened",
                    "source_branch": "feature/conflict",
                    "target_branch": "develop",
                    "merge_status": "cannot_be_merged",
                    "has_conflicts": true
                }
                """;

            mockServer.expect(requestTo(baseUrl() + "/projects/" +
                            PROJECT_ID + "/merge_requests/" + MERGE_REQUEST_ID))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            MergeRequest result = client.getMergeRequest(PROJECT_ID, MERGE_REQUEST_ID);

            mockServer.verify();

            assertThat(result)
                    .hasFieldOrPropertyWithValue("hasConflicts", true)
                    .hasFieldOrPropertyWithValue("mergeStatus", "cannot_be_merged");
        }
    }

    @Nested
    @DisplayName("getChanges")
    class GetChangesIntegrationTests {

        @Test
        @DisplayName("Должен вернуть список изменений файлов для MR")
        void givenGitlabApiReturnsChangesWhenGetChangesThenParseSuccessfully() {
            String jsonResponse = """
                {
                    "changes": [
                        {
                            "old_path": "src/main/java/File1.java",
                            "new_path": "src/main/java/File1.java",
                            "new_file": false,
                            "deleted_file": false,
                            "renamed_file": false
                        },
                        {
                            "old_path": "src/test/java/NewTest.java",
                            "new_path": "src/test/java/NewTest.java",
                            "new_file": true,
                            "deleted_file": false,
                            "renamed_file": false
                        }
                    ]
                }
                """;

            mockServer.expect(requestTo(baseUrl() + "/projects/" +
                            PROJECT_ID + "/merge_requests/" + MERGE_REQUEST_ID + "/changes"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            List<MergeRequestDiff> result = client.getChanges(PROJECT_ID, MERGE_REQUEST_ID);

            mockServer.verify();

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
        void givenGitlabApiReturnsEmptyChangesWhenGetChangesThenReturnEmptyList() {
            String jsonResponse = """
                {
                    "changes": []
                }
                """;

            mockServer.expect(requestTo(baseUrl() + "/projects/" +
                            PROJECT_ID + "/merge_requests/" + MERGE_REQUEST_ID + "/changes"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

            List<MergeRequestDiff> result = client.getChanges(PROJECT_ID, MERGE_REQUEST_ID);

            mockServer.verify();

            assertThat(result)
                    .isNotNull()
                    .isEmpty();
        }
    }
}
