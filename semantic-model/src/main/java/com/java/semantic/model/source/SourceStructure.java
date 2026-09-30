package com.java.semantic.model.source;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source-plan roots and factual projection counts, not generated narrative. */
public record SourceStructure(List<String> importedSourceRoots, Map<String, Long> packageCounts,
        Map<String, Long> entryPointKindCounts) {
    public SourceStructure {
        importedSourceRoots = List.copyOf(Objects.requireNonNull(importedSourceRoots, "source roots are required"));
        packageCounts = Map.copyOf(Objects.requireNonNull(packageCounts, "package counts are required"));
        entryPointKindCounts = Map.copyOf(Objects.requireNonNull(entryPointKindCounts, "entry point counts are required"));
        if (packageCounts.values().stream().anyMatch(count -> count < 0)
                || entryPointKindCounts.values().stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("structure counts cannot be negative");
        }
    }
}
