package com.java.semantic.indexer.incremental;

import java.util.List;
import java.util.Objects;

/** Deterministic, non-overlapping build actions for one selected revision. */
public record IncrementalIndexPlan(boolean fullRepository, List<String> reanalyzePaths, List<String> copyPaths,
                                   List<String> deletedPaths, List<String> diagnosticReasons) {
    public IncrementalIndexPlan {
        reanalyzePaths = sorted(reanalyzePaths);
        copyPaths = sorted(copyPaths);
        deletedPaths = sorted(deletedPaths);
        diagnosticReasons = sorted(diagnosticReasons);
        if (overlaps(reanalyzePaths, copyPaths) || overlaps(reanalyzePaths, deletedPaths)
                || overlaps(copyPaths, deletedPaths)) {
            throw new IllegalArgumentException("incremental path actions must be pairwise disjoint");
        }
    }

    private static List<String> sorted(List<String> paths) {
        return List.copyOf(Objects.requireNonNull(paths, "paths are required").stream().sorted().toList());
    }

    private static boolean overlaps(List<String> left, List<String> right) {
        return left.stream().anyMatch(right::contains);
    }
}
