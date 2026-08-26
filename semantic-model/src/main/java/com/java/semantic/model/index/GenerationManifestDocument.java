package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record GenerationManifestDocument(
        RepositoryId repositoryId,
        RepositoryRevision sourceRevision,
        GenerationId generationId,
        String ownerJobId,
        GenerationWriteState writeState,
        long writeEpoch,
        IndexSchemaVersion schemaVersion,
        List<ProjectionVersion> projectionVersions,
        Map<String, Long> sealedCollectionCounts,
        ManifestDigest identityDigest,
        Optional<String> validationResult,
        Optional<Instant> validatedAt) {

    public GenerationManifestDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        sourceRevision = Objects.requireNonNull(sourceRevision, "source revision is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        ownerJobId = ModelValidation.requiredText(ownerJobId, "owner job id");
        writeState = Objects.requireNonNull(writeState, "write state is required");
        ModelValidation.require(writeEpoch >= 0, "write epoch must not be negative");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schema version is required");
        projectionVersions = List.copyOf(Objects.requireNonNull(projectionVersions, "projection versions are required"));
        sealedCollectionCounts = Map.copyOf(Objects.requireNonNull(sealedCollectionCounts, "sealed collection counts are required"));
        identityDigest = Objects.requireNonNull(identityDigest, "identity digest is required");
        validationResult = Objects.requireNonNull(validationResult, "validation result is required");
        validatedAt = Objects.requireNonNull(validatedAt, "validation time is required");
        ModelValidation.require(!projectionVersions.isEmpty(), "projection versions must not be empty");
        for (Long count : sealedCollectionCounts.values()) {
            ModelValidation.require(count >= 0, "sealed collection count must not be negative");
        }
    }
}
