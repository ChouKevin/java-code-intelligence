package com.java.semantic.indexer.job;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/** Typed durable payload for Git work; it is deliberately independent of semantic generations. */
public record GitEvidenceJob(Optional<GitEvidenceId> catalogId, Optional<String> branch, Optional<RepositoryRevision> revision,
                             Optional<GitEvidenceId> evidenceId) {
    public GitEvidenceJob {
        catalogId = Objects.requireNonNull(catalogId, "catalog id is required");
        branch = Objects.requireNonNull(branch, "branch is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        evidenceId = Objects.requireNonNull(evidenceId, "evidence id is required");
    }

    public static GitEvidenceJob refs() { return new GitEvidenceJob(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }
    public static GitEvidenceJob history(GitEvidenceId catalogId, String branch, RepositoryRevision revision) {
        return new GitEvidenceJob(Optional.of(catalogId), Optional.of(branch), Optional.of(revision), Optional.empty());
    }
}
