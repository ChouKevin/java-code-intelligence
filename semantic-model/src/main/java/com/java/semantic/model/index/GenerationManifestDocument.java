package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceStructure;

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
        Optional<Instant> validatedAt,
        AnalysisFingerprint analysisFingerprint,
        Optional<SemanticAnalysisEvidence> analysisEvidence,
        Optional<SourceSnapshotMembership> sourceSnapshot,
        Optional<ProjectGuideMembership> projectGuide,
        Optional<SourceCoverage> coverage,
        Optional<SourceStructure> structure) {

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
        analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysis fingerprint is required");
        analysisEvidence = Objects.requireNonNull(analysisEvidence, "analysis evidence is required");
        sourceSnapshot = Objects.requireNonNull(sourceSnapshot, "source snapshot is required");
        projectGuide = Objects.requireNonNull(projectGuide, "project guide is required");
        coverage = Objects.requireNonNull(coverage, "source coverage is required");
        structure = Objects.requireNonNull(structure, "source structure is required");
        if (analysisEvidence.isPresent()) {
            ModelValidation.require(analysisFingerprint.digest().equals(analysisEvidence.orElseThrow().fingerprintDigest()),
                    "analysis evidence fingerprint must match analysis fingerprint");
        }
        ModelValidation.require(!projectionVersions.isEmpty(), "projection versions must not be empty");
        for (Long count : sealedCollectionCounts.values()) {
            ModelValidation.require(count >= 0, "sealed collection count must not be negative");
        }
        boolean validated = validationResult.filter("VALID"::equals).isPresent();
        if (validated || writeState == GenerationWriteState.SEALED_VALID) {
            ModelValidation.require(analysisEvidence.isPresent(),
                    "validated generation requires semantic analysis evidence");
            ModelValidation.require(sourceSnapshot.isPresent() && projectGuide.isPresent()
                            && coverage.isPresent() && structure.isPresent(),
                    "validated generation requires sealed source membership and objective coverage");
            ModelValidation.require(sourceRevision.equals(sourceSnapshot.orElseThrow().revision()),
                    "source snapshot revision must match semantic generation");
        }
    }
}
