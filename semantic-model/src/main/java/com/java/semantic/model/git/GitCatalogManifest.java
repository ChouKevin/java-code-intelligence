package com.java.semantic.model.git;

import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import java.time.Instant;
import java.util.Objects;

public record GitCatalogManifest(
        GitEvidenceId catalogId,
        RepositoryId repositoryId,
        Instant observedAt,
        GitEvidenceState state,
        int gitEvidenceVersion,
        GitEvidenceOwnership ownership) {

    public static final int VERSION = IndexSchemaContract.GIT_EVIDENCE_VERSION;

    public GitCatalogManifest {
        catalogId = Objects.requireNonNull(catalogId, "catalog id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        observedAt = Objects.requireNonNull(observedAt, "catalog observation time is required");
        state = Objects.requireNonNull(state, "catalog state is required");
        ownership = Objects.requireNonNull(ownership, "catalog ownership is required");
        if (gitEvidenceVersion != VERSION || ownership.scope() != GitPublicationScope.STANDALONE) {
            throw new IllegalArgumentException("catalog evidence must use the current standalone Git contract");
        }
    }
}
