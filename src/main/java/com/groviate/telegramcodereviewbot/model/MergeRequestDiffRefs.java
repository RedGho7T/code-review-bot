package com.groviate.telegramcodereviewbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestDiffRefs {
    private String baseSha;
    private String startSha;
    private String headSha;
}
