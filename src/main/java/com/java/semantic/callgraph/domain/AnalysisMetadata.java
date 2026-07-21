package com.java.semantic.callgraph.domain;

import java.time.Instant;

public record AnalysisMetadata(
        String repoId,
        Instant analyzedAt) {
}
