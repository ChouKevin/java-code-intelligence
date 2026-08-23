package com.java.semantic.semantic.application;

import com.java.semantic.syntax.domain.ExactSourceDeclaration;

import java.util.List;
import java.util.Objects;

/** cache 保存的完整未分頁內部 reference 分析 */
public record InternalReferenceAnalysis(
        ExactSourceDeclaration targetDeclaration,
        InternalReferenceStatus status,
        int totalReferenceCount,
        List<InternalReferenceGroup> groups,
        List<InternalReferenceIssueSummary> issueSummaries,
        int rawLocationCount,
        int repositoryLocalReferenceCount,
        int hitFileCount,
        int entryWeight) {

    public InternalReferenceAnalysis {
        targetDeclaration = Objects.requireNonNull(targetDeclaration, "targetDeclaration is required");
        status = Objects.requireNonNull(status, "status is required");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups are required"));
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
        if (totalReferenceCount < 0 || rawLocationCount < 0 || repositoryLocalReferenceCount < 0
                || hitFileCount < 0 || entryWeight < 1) {
            throw new IllegalArgumentException("internal reference analysis counts are invalid");
        }
    }
}
