package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryRevision;

import java.util.List;
import java.util.Objects;

/** Complete direct-comparison payload produced from two exact commit trees. */
public record GitPreparedComparison(RepositoryRevision previous, RepositoryRevision current, GitComparisonAncestry ancestry,
                                    List<GitSnapshotEntry> previousEntries, List<GitSnapshotEntry> currentEntries,
                                    List<GitComparisonChange> changes) {
    public GitPreparedComparison {
        previous = Objects.requireNonNull(previous, "previous revision is required");
        current = Objects.requireNonNull(current, "current revision is required");
        ancestry = Objects.requireNonNull(ancestry, "comparison ancestry is required");
        previousEntries = List.copyOf(Objects.requireNonNull(previousEntries, "previous snapshot entries are required"));
        currentEntries = List.copyOf(Objects.requireNonNull(currentEntries, "current snapshot entries are required"));
        changes = List.copyOf(Objects.requireNonNull(changes, "comparison changes are required"));
    }
}
