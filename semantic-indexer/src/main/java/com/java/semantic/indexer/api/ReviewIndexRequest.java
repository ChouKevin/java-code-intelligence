package com.java.semantic.indexer.api;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewSelection;
import java.util.Objects;

/** HTTP request binds the same selection union used by the worker and Query. */
public record ReviewIndexRequest(SelectionInput selection) {
    public ReviewSelection reviewSelection() {
        if (Objects.isNull(selection)) {
            throw new InvalidSelectionException();
        }
        return selection.toSelection();
    }

    public record SelectionInput(ReviewComparisonType kind, String revision, String beforeRevision, String afterRevision) {
        public ReviewSelection toSelection() {
            if (Objects.isNull(kind)) {
                throw new InvalidSelectionException();
            }
            if (kind == ReviewComparisonType.COMMIT) {
                if (Objects.isNull(revision) || Objects.nonNull(beforeRevision) || Objects.nonNull(afterRevision)) {
                    throw new InvalidSelectionException();
                }
                try {
                    return ReviewSelection.commit(RepositoryRevision.ofSha(revision));
                } catch (IllegalArgumentException exception) {
                    throw new InvalidSelectionException();
                }
            }
            if (kind != ReviewComparisonType.RANGE || Objects.nonNull(revision) || Objects.isNull(beforeRevision) || Objects.isNull(afterRevision)) {
                throw new InvalidSelectionException();
            }
            try {
                return ReviewSelection.range(RepositoryRevision.ofSha(beforeRevision), RepositoryRevision.ofSha(afterRevision));
            } catch (IllegalArgumentException exception) {
                throw new InvalidSelectionException();
            }
        }
    }

    public static final class InvalidSelectionException extends RuntimeException {
        public InvalidSelectionException() {
            super("Invalid review selection");
        }
    }
}
