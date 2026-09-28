package com.java.semantic.indexer.api;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewSelection;
import java.util.Objects;

/** HTTP request binds the same selection union used by the worker and Query. */
public record ReviewIndexRequest(SelectionInput selection) {
    public ReviewSelection reviewSelection() {
        return Objects.requireNonNull(selection, "review selection is required").toSelection();
    }

    public record SelectionInput(ReviewComparisonType kind, String revision, String beforeRevision, String afterRevision) {
        public ReviewSelection toSelection() {
            ReviewComparisonType selectedKind = Objects.requireNonNull(kind, "review kind is required");
            if (selectedKind == ReviewComparisonType.COMMIT) {
                if (Objects.isNull(revision) || Objects.nonNull(beforeRevision) || Objects.nonNull(afterRevision)) {
                    throw new IllegalArgumentException("COMMIT requires revision and forbids range revisions");
                }
                return ReviewSelection.commit(RepositoryRevision.ofSha(revision));
            }
            if (Objects.nonNull(revision) || Objects.isNull(beforeRevision) || Objects.isNull(afterRevision)) {
                throw new IllegalArgumentException("RANGE requires beforeRevision and afterRevision only");
            }
            return ReviewSelection.range(RepositoryRevision.ofSha(beforeRevision), RepositoryRevision.ofSha(afterRevision));
        }
    }
}
