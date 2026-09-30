package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Author-provided provenance: it is not a verification of narrative accuracy. */
public record ProjectGuideProvenance(int formatVersion, int promptVersion, RepositoryId repositoryId,
        RepositoryRevision analyzedRevision, Instant generatedAt, SourceScope sourceScope) {
    public ProjectGuideProvenance {
        if (formatVersion != 1 || promptVersion != 1) {
            throw new IllegalArgumentException("unsupported guide provenance version");
        }
        Objects.requireNonNull(repositoryId, "repository id is required");
        Objects.requireNonNull(analyzedRevision, "analyzed revision is required");
        Objects.requireNonNull(generatedAt, "generation time is required");
        Objects.requireNonNull(sourceScope, "source scope is required");
    }

    public record SourceScope(List<String> includedPaths, List<String> excludedPaths, List<String> limitations) {
        public SourceScope {
            includedPaths = List.copyOf(Objects.requireNonNull(includedPaths, "included paths are required"));
            excludedPaths = List.copyOf(Objects.requireNonNull(excludedPaths, "excluded paths are required"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
            if (includedPaths.stream().anyMatch(Objects::isNull) || excludedPaths.stream().anyMatch(Objects::isNull)
                    || limitations.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("source scope strings are required");
            }
        }
    }
}
