package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "code-review", name = "rag-economy-mode", havingValue = "false")
public class FullMergeRequestRagContextProvider implements MergeRequestRagContextProvider {

    private final RagContextService ragContextService;

    public FullMergeRequestRagContextProvider(RagContextService ragContextService) {
        this.ragContextService = ragContextService;
    }

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

    private boolean isCandidate(MergeRequestDiff diff) {
        return diff != null
                && !diff.isDeletedFile()
                && isNotBlank(diff.getDiff());
    }

    private String toRagBlock(MergeRequestDiff diff) {
        String fileRag = ragContextService.getContextForCode(diff.getDiff());
        if (!isNotBlank(fileRag)) {
            return "";
        }

        String filePath = resolveFilePath(diff);
        return "\n--- Стандарты для файла: " + filePath + " ---" + fileRag;
    }

    private String resolveFilePath(MergeRequestDiff diff) {
        if (diff.getNewPath() != null) return diff.getNewPath();
        if (diff.getOldPath() != null) return diff.getOldPath();
        return "<unknown>";
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
