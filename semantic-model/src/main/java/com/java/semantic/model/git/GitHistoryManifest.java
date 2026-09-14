package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.time.Instant;
import java.util.Objects;

public record GitHistoryManifest(GitEvidenceId historyId, GitEvidenceId catalogId, RepositoryId repositoryId,
                                 String branch, RepositoryRevision revision, Instant preparedAt,
                                 GitEvidenceState state, int gitEvidenceVersion, long total) {
    public static final int VERSION = 1;

    public GitHistoryManifest {
        historyId = Objects.requireNonNull(historyId, "history id is required");
        catalogId = Objects.requireNonNull(catalogId, "catalog id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        branch = ModelValidation.requiredText(branch, "git branch name");
        revision = Objects.requireNonNull(revision, "history revision is required");
        preparedAt = Objects.requireNonNull(preparedAt, "history preparation time is required");
        state = Objects.requireNonNull(state, "history state is required");
        if (gitEvidenceVersion != VERSION || total < 0) {
            throw new IllegalArgumentException("invalid git history manifest");
        }
    }
}
