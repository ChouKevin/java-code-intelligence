package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Optional;

/** Exact compare-and-set input for publishing one sealed immutable generation. */
public record PublishGenerationCommand(
        RepositoryId repositoryId,
        RepositoryRevision targetRevision,
        GenerationId targetGenerationId,
        String jobId,
        Optional<PublishedGenerationPointer> expectedParent,
        ManifestDigest sealedManifestDigest) {

    public PublishGenerationCommand {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        targetRevision = Objects.requireNonNull(targetRevision, "target revision is required");
        targetGenerationId = Objects.requireNonNull(targetGenerationId, "target generation id is required");
        jobId = ModelValidation.requiredText(jobId, "job id");
        expectedParent = Objects.requireNonNull(expectedParent, "expected parent is required");
        sealedManifestDigest = Objects.requireNonNull(sealedManifestDigest, "sealed manifest digest is required");
    }
}
