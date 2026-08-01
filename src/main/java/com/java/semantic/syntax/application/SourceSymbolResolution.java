package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** resolver 完整 matching set、狀態與固定形狀 performance evidence */
public record SourceSymbolResolution(
        SourceSymbolResolutionStatus status,
        List<SourceContextCandidate> contextCandidates,
        List<SourceSymbolCandidate> candidates,
        List<SourceSymbolIssueSummary> issueSummaries,
        Optional<String> selectedSourceFile,
        long selectedSourceBytes,
        int matchingSymbolCount) {

    public SourceSymbolResolution {
        status = Objects.requireNonNull(status, "status is required");
        contextCandidates = List.copyOf(Objects.requireNonNull(
                contextCandidates, "contextCandidates are required"));
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
        selectedSourceFile = Objects.requireNonNull(selectedSourceFile, "selectedSourceFile is required");
        if (selectedSourceBytes < 0 || matchingSymbolCount < 0) {
            throw new IllegalArgumentException("resolution metrics must not be negative");
        }
    }
}
