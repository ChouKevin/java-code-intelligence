package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryRevision;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete direct-comparison payload produced from two exact commit trees. */
public record GitPreparedComparison(Optional<RepositoryRevision> previous, RepositoryRevision current, GitComparisonAncestry ancestry,
                                    List<GitSnapshotEntry> previousEntries, List<GitSnapshotEntry> currentEntries,
                                    List<GitComparisonChange> changes, GitComparisonPolicyCoverage policyCoverage) {
    public GitPreparedComparison {
        previous = Objects.requireNonNull(previous, "previous revision is required");
        current = Objects.requireNonNull(current, "current revision is required");
        ancestry = Objects.requireNonNull(ancestry, "comparison ancestry is required");
        if (previous.isEmpty() != (ancestry == GitComparisonAncestry.EMPTY_TREE)) {
            throw new IllegalArgumentException("empty previous revision requires EMPTY_TREE ancestry");
        }
        if (previous.isEmpty() && !previousEntries.isEmpty()) {
            throw new IllegalArgumentException("empty tree must not contain snapshot entries");
        }
        previousEntries = List.copyOf(Objects.requireNonNull(previousEntries, "previous snapshot entries are required"));
        currentEntries = List.copyOf(Objects.requireNonNull(currentEntries, "current snapshot entries are required"));
        changes = List.copyOf(Objects.requireNonNull(changes, "comparison changes are required"));
        policyCoverage = Objects.requireNonNull(policyCoverage, "comparison policy coverage is required");
    }
}
