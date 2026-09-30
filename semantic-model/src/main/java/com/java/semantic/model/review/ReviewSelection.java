package com.java.semantic.model.review;

import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Objects;
import java.util.Optional;

/** Requested comparison identity, independent of the repository's mutable current pointer. */
public record ReviewSelection(ReviewComparisonType kind, Optional<RepositoryRevision> beforeRevision,
                              RepositoryRevision afterRevision) {
    public ReviewSelection {
        kind = Objects.requireNonNull(kind, "review kind is required");
        beforeRevision = Objects.requireNonNull(beforeRevision, "requested before revision is required");
        afterRevision = Objects.requireNonNull(afterRevision, "requested after revision is required");
        if ((kind == ReviewComparisonType.COMMIT && beforeRevision.isPresent())
                || (kind == ReviewComparisonType.RANGE && beforeRevision.isEmpty())) {
            throw new IllegalArgumentException("COMMIT forbids before revision and RANGE requires it");
        }
    }

    /** Stable persisted identity shared by review admission and publication. */
    public String selectionKey() {
        return kind.name() + ":" + beforeRevision.map(RepositoryRevision::value).orElse("")
                + ":" + afterRevision.value();
    }

    public static ReviewSelection commit(RepositoryRevision revision) {
        return new ReviewSelection(ReviewComparisonType.COMMIT, Optional.empty(), revision);
    }

    public static ReviewSelection range(RepositoryRevision before, RepositoryRevision after) {
        return new ReviewSelection(ReviewComparisonType.RANGE, Optional.of(before), after);
    }
}
