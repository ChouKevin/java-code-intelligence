package com.java.semantic.model.query;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

public record SelectedGeneration(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        GenerationId generationId,
        ManifestDigest manifestDigest) {

    public SelectedGeneration {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "repository revision is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        manifestDigest = Objects.requireNonNull(manifestDigest, "manifest digest is required");
    }
}
