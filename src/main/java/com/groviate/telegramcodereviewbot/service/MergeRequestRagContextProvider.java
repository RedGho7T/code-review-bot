package com.groviate.telegramcodereviewbot.service;

import com.groviate.telegramcodereviewbot.model.MergeRequestDiff;

import java.util.List;

public interface MergeRequestRagContextProvider {
    String buildRagContext(List<MergeRequestDiff> diffs);
}
