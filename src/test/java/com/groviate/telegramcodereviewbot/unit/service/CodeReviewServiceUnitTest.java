package com.groviate.telegramcodereviewbot.unit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groviate.telegramcodereviewbot.config.CodeReviewProperties;
import com.groviate.telegramcodereviewbot.model.CodeReviewResult;
import com.groviate.telegramcodereviewbot.model.MergeRequest;
import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import com.groviate.telegramcodereviewbot.service.AiChatGateway;
import com.groviate.telegramcodereviewbot.service.CodeReviewService;
import com.groviate.telegramcodereviewbot.service.MergeRequestRagContextProvider;
import com.groviate.telegramcodereviewbot.service.PromptTemplateService;
import com.groviate.telegramcodereviewbot.service.ReviewMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewServiceUnitTest {

    @Mock
    PromptTemplateService promptTemplateService;
    @Mock
    CodeReviewProperties props;
    @Mock
    MergeRequestRagContextProvider ragContextProvider;
    @Mock
    AiChatGateway aiChatGateway;
    @Mock
    ReviewMetricsService metrics;

    private CodeReviewService service;

    @BeforeEach
    void setUp() {
        service = new CodeReviewService(
                promptTemplateService,
                new ObjectMapper(),
                props,
                ragContextProvider,
                aiChatGateway,
                metrics
        );
    }

    @Test
    @DisplayName("Если изменений нет (пустой список) -> возвращается пустой результат и AI не вызывается")
    void givenEmptyDiffsWhenAnalyzeCodeThenReturnsEmptyReviewAndDoesNotCallAi() {
        CodeReviewResult result = service.analyzeCode(MergeRequest.builder().title("t").description("d").build(), List.of());

        assertThat(result).isNotNull();
        assertThat(result.getScore()).isZero();
        assertThat(result.getSummary()).containsIgnoringCase("нет изменений");

        verifyNoInteractions(aiChatGateway);
        verifyNoInteractions(promptTemplateService);
        verifyNoInteractions(ragContextProvider);
    }

    @Test
    @DisplayName("Если RAG отключён в промпт передаётся пустой контекст")
    void givenRagDisabledWhenAnalyzeCodeThenDoesNotCallRagProviderAndPassesEmptyRagToPrompt() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");
        when(aiChatGateway.ask("SYS", "USER"))
                .thenReturn("{\"score\":9,\"summary\":\"ok\",\"suggestions\":[]}");

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getScore()).isEqualTo(9);
        assertThat(result.getSummary()).isEqualTo("ok");

        verify(ragContextProvider, never()).buildRagContext(anyList());

        ArgumentCaptor<String> ragCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptTemplateService).preparePrompt(eq(diffs), eq("MR"), eq("desc"), ragCaptor.capture());
        assertThat(ragCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Если AI вернул пустой ответ -> возвращается пустой результат ревью")
    void givenAiReturnsBlankWhenAnalyzeCodeThenReturnsEmptyReview() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        when(aiChatGateway.ask("SYS", "USER")).thenReturn("   ");

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getScore()).isZero();
        assertThat(result.getSummary()).containsIgnoringCase("пустой");

        verify(aiChatGateway).ask("SYS", "USER");
    }

    @Test
    @DisplayName("Если AI вернул JSON с лишним текстом -> результат корректно парсится")
    void givenAiReturnsJsonWithNoiseWhenAnalyzeCodeThenParsesAndReturnsResult() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        String aiResponse = "Some text before\n{\"score\":7,\"summary\":\"fine\",\"suggestions\":[]}\ntext after";
        when(aiChatGateway.ask("SYS", "USER")).thenReturn(aiResponse);

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getScore()).isEqualTo(7);
        assertThat(result.getSummary()).isEqualTo("fine");
        assertThat(result.getSuggestions()).isNotNull();
    }

    private static MergeRequestDiff oneDiff() {
        return MergeRequestDiff.builder()
                .oldPath("src/A.java")
                .newPath("src/A.java")
                .diff("+test")
                .newFile(false)
                .deletedFile(false)
                .renamedFile(false)
                .build();
    }

    @Test
    @DisplayName("Если JSON содержит экранированные кавычки (\\\") внутри summary -> корректно извлекается и парсится")
    void givenAiReturnsJsonWithEscapedQuotesWhenAnalyzeCodeThenParsesCorrectly() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        String aiResponse = "noise\n{\"score\":7,\"summary\":\"He said \\\"Hi\\\"\",\"suggestions\":[]}\nnoise";
        when(aiChatGateway.ask("SYS", "USER")).thenReturn(aiResponse);

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getScore()).isEqualTo(7);
        assertThat(result.getSummary()).isEqualTo("He said \"Hi\"");
        assertThat(result.getSuggestions()).isNotNull();
    }

    @Test
    @DisplayName("Если AI не прислал metadata -> service добавляет metadata сам")
    void givenAiWithoutMetadataWhenAnalyzeCodeThenMetadataIsAdded() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        when(aiChatGateway.ask("SYS", "USER"))
                .thenReturn("{\"score\":9,\"summary\":\"ok\",\"suggestions\":[]}");

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getMetadata()).isNotBlank();
        assertThat(result.getMetadata()).contains("files=");
    }

    @Test
    @DisplayName("Если AI вернул suggestions=null -> service заменяет на пустой список")
    void givenAiSuggestionsNullWhenAnalyzeCodeThenSuggestionsBecomesEmptyList() {
        when(props.isRagEnabled()).thenReturn(false);

        List<MergeRequestDiff> diffs = List.of(oneDiff());
        MergeRequest mr = MergeRequest.builder().title("MR").description("desc").build();

        when(promptTemplateService.getSystemPrompt()).thenReturn("SYS");
        when(promptTemplateService.preparePrompt(anyList(), anyString(), anyString(), anyString()))
                .thenReturn("USER");

        when(aiChatGateway.ask("SYS", "USER"))
                .thenReturn("{\"score\":5,\"summary\":\"ok\",\"suggestions\":null}");

        CodeReviewResult result = service.analyzeCode(mr, diffs);

        assertThat(result.getSuggestions()).isNotNull();
        assertThat(result.getSuggestions()).isEmpty();
    }
}