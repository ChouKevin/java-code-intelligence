package com.java.semantic.indexer.job;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/** Durable evidence of the only repository pointer mutation a job is allowed to reconcile. */
public record IndexPublicationIntent(
        IndexJobId jobId,
        IndexJobOperation operation,
        RepositoryId repositoryId,
        RepositoryRevision targetRevision,
        GenerationId targetGenerationId,
        ManifestDigest targetManifestDigest,
        Optional<PublishedGenerationPointer> expectedParent,
        Optional<PublishedGenerationPointer> expectedCurrent,
        Optional<PublishedGenerationPointer> expectedRollback) {
    public IndexPublicationIntent {
        jobId = Objects.requireNonNull(jobId, "job id is required");
        operation = Objects.requireNonNull(operation, "operation is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        targetRevision = Objects.requireNonNull(targetRevision, "target revision is required");
        targetGenerationId = Objects.requireNonNull(targetGenerationId, "target generation id is required");
        targetManifestDigest = Objects.requireNonNull(targetManifestDigest, "target manifest digest is required");
        expectedParent = Objects.requireNonNull(expectedParent, "expected parent is required");
        expectedCurrent = Objects.requireNonNull(expectedCurrent, "expected current is required");
        expectedRollback = Objects.requireNonNull(expectedRollback, "expected rollback is required");
        if (operation == IndexJobOperation.BUILD && (expectedCurrent.isPresent() || expectedRollback.isPresent())) {
            throw new IllegalArgumentException("build intent only has an expected parent");
        }
        if (operation == IndexJobOperation.ROLLBACK && (expectedCurrent.isEmpty() || expectedRollback.isEmpty())) {
            throw new IllegalArgumentException("rollback intent requires both exact pointers");
        }
    }
}
