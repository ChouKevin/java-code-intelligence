package com.java.semantic.model.source;

import com.java.semantic.model.git.GitSnapshotEntry;
import java.util.List;
import java.util.Objects;

/** Exact Git tree selection plus an aggregate count with no excluded paths or bytes. */
public record TrackedSourceInventory(List<GitSnapshotEntry> candidates, long excludedOrUnsupported) {
    public TrackedSourceInventory {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidate entries are required"));
        if (excludedOrUnsupported < 0) {
            throw new IllegalArgumentException("excluded count cannot be negative");
        }
    }
}
