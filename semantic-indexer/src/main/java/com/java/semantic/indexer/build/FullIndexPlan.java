package com.java.semantic.indexer.build;

import com.java.semantic.model.index.SourceArtifactDocument;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable, deterministic set of source inputs for one complete generation. */
public record FullIndexPlan(Path repositoryRoot, List<SourceInput> sources) {

    public FullIndexPlan {
        repositoryRoot = Objects.requireNonNull(repositoryRoot, "repository root is required").toAbsolutePath().normalize();
        sources = List.copyOf(Objects.requireNonNull(sources, "sources are required"));
    }

    public record SourceInput(String sourcePath, Path path, SourceArtifactDocument contentArtifact) {
        public SourceInput {
            sourcePath = Objects.requireNonNull(sourcePath, "source path is required");
            path = Objects.requireNonNull(path, "path is required").toAbsolutePath().normalize();
            contentArtifact = Objects.requireNonNull(contentArtifact, "content artifact is required");
        }
    }
}
