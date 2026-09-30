package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Objects;
import java.util.Optional;

/** Guide metadata is exposed only when its entire file passed admission. */
public record ProjectGuideMembership(ProjectGuideState state, Optional<String> path,
        Optional<String> digest, Optional<RepositoryRevision> importedRevision,
        Optional<ProjectGuideProvenance> provenance, String freshness) {
    public ProjectGuideMembership {
        Objects.requireNonNull(state, "guide state is required");
        Objects.requireNonNull(path, "guide path is required");
        Objects.requireNonNull(digest, "guide digest is required");
        Objects.requireNonNull(importedRevision, "imported revision is required");
        Objects.requireNonNull(provenance, "guide provenance is required");
        if (!"NOT_VERIFIED".equals(freshness)) {
            throw new IllegalArgumentException("guide freshness is not verified");
        }
        boolean available = state == ProjectGuideState.AVAILABLE;
        boolean allPresent = path.isPresent() && digest.isPresent() && importedRevision.isPresent()
                && provenance.isPresent();
        boolean anyPresent = path.isPresent() || digest.isPresent() || importedRevision.isPresent()
                || provenance.isPresent();
        if (available ? !allPresent : anyPresent) {
            throw new IllegalArgumentException("only an available guide has readable provenance and membership");
        }
    }

    public static ProjectGuideMembership unavailable(ProjectGuideState state) {
        if (state == ProjectGuideState.AVAILABLE) {
            throw new IllegalArgumentException("available guide requires provenance");
        }
        return new ProjectGuideMembership(state, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), "NOT_VERIFIED");
    }
}
