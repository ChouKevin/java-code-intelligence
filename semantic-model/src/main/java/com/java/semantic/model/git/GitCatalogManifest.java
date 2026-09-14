package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryId;

import java.time.Instant;
import java.util.Objects;

public record GitCatalogManifest(GitEvidenceId catalogId, RepositoryId repositoryId, Instant observedAt,
                                 GitEvidenceState state, int gitEvidenceVersion) {
    public static final int VERSION = 1;

    public GitCatalogManifest {
        catalogId = Objects.requireNonNull(catalogId, "catalog id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        observedAt = Objects.requireNonNull(observedAt, "catalog observation time is required");
        state = Objects.requireNonNull(state, "catalog state is required");
        if (gitEvidenceVersion != VERSION) {
            throw new IllegalArgumentException("unsupported git evidence version");
        }
    }
}
