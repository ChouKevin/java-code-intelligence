package com.java.semantic.syntax.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.List;
import java.util.Objects;

/** analyzed revision 綁定的 source symbol HTTP projection input */
public record RevisionBoundSourceSymbolResolution(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SourceSymbolResolutionStatus status,
        List<NavigableSourceContextCandidate> contextCandidates,
        SourceContextCandidateLimits contextCandidateLimits,
        List<NavigableSourceSymbolCandidate> candidates,
        List<SourceSymbolIssueSummary> issueSummaries) {

    public RevisionBoundSourceSymbolResolution {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        status = Objects.requireNonNull(status, "status is required");
        contextCandidates = List.copyOf(Objects.requireNonNull(
                contextCandidates, "contextCandidates are required"));
        contextCandidateLimits = Objects.requireNonNull(
                contextCandidateLimits, "contextCandidateLimits is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
    }
}
