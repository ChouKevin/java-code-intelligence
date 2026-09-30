package com.java.semantic.indexer.job;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/** Durable metadata refresh payload, independent of semantic generation preparation. */
public record GitEvidenceJob(Optional<GitEvidenceId> catalogId, Optional<String> branch,
        Optional<RepositoryRevision> revision, Optional<GitEvidenceId> evidenceId,
        Optional<GitHistoryManifest> metadataResult) {
    public GitEvidenceJob {
        catalogId = Objects.requireNonNull(catalogId, "catalog id is required");
        branch = Objects.requireNonNull(branch, "branch is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        evidenceId = Objects.requireNonNull(evidenceId, "evidence id is required");
        metadataResult = Objects.requireNonNull(metadataResult, "metadata result is required");
        if (branch.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("metadata branch must not be blank");
        }
        if (metadataResult.isPresent()) {
            GitHistoryManifest result = metadataResult.orElseThrow();
            if (result.state() != GitEvidenceState.READY || result.total() < 1L
                    || !catalogId.equals(Optional.of(result.catalogId()))
                    || !evidenceId.equals(Optional.of(result.historyId()))
                    || !branch.equals(Optional.of(result.branch()))
                    || !revision.equals(Optional.of(result.revision()))) {
                throw new IllegalArgumentException("metadata result must match the prepared catalog and history");
            }
        }
    }

    public static GitEvidenceJob metadata(String branch) {
        return new GitEvidenceJob(Optional.empty(), Optional.of(branch), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
