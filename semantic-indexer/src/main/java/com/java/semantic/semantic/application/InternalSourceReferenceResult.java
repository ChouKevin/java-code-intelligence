package com.java.semantic.semantic.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;

import java.util.List;
import java.util.Objects;

/** revision-bound 內部 reference 分頁結果與完整分析計數 */
public record InternalSourceReferenceResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        ExactSourceDeclaration targetDeclaration,
        InternalReferenceStatus status,
        int totalReferenceCount,
        List<InternalReferenceGroup> groups,
        InternalReferencePage page,
        List<InternalReferenceIssueSummary> issueSummaries,
        InternalReferenceCacheMetadata cache,
        int rawLocationCount,
        int repositoryLocalReferenceCount,
        int hitFileCount,
        int totalGroupCount) {

    public InternalSourceReferenceResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        targetDeclaration = Objects.requireNonNull(targetDeclaration, "targetDeclaration is required");
        status = Objects.requireNonNull(status, "status is required");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups are required"));
        page = Objects.requireNonNull(page, "page is required");
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
        cache = Objects.requireNonNull(cache, "cache is required");
        if (totalReferenceCount < 0 || rawLocationCount < 0 || repositoryLocalReferenceCount < 0
                || hitFileCount < 0 || totalGroupCount < groups.size()) {
            throw new IllegalArgumentException("internal reference result counts are invalid");
        }
        if (groups.size() != page.returnedCount() || totalGroupCount != page.totalCount()) {
            throw new IllegalArgumentException("groups must match page metadata");
        }
    }
}
