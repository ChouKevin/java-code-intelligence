package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Instant;
import java.util.Objects;

/** The single published repository pointer and its bounded rollback value. */
public record PublishedGenerationPointer(
        RepositoryRevision revision,
        GenerationId generationId,
        ManifestDigest manifestDigest,
        String committedJobId,
        Instant publishedAt) {

    public PublishedGenerationPointer {
        revision = Objects.requireNonNull(revision, "repository revision is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        manifestDigest = Objects.requireNonNull(manifestDigest, "manifest digest is required");
        committedJobId = com.java.semantic.model.support.ModelValidation.requiredText(committedJobId, "committed job id");
        publishedAt = Objects.requireNonNull(publishedAt, "publication time is required");
    }
}
