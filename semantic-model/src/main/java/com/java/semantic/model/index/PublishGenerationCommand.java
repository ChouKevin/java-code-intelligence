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
        String activeJobId,
        String activeWorkerId,
        RepositoryFence activeFence,
        Optional<PublishedGenerationPointer> expectedParent,
        ManifestDigest sealedManifestDigest) {

    public PublishGenerationCommand {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        targetRevision = Objects.requireNonNull(targetRevision, "target revision is required");
        targetGenerationId = Objects.requireNonNull(targetGenerationId, "target generation id is required");
        activeJobId = ModelValidation.requiredText(activeJobId, "active job id");
        activeWorkerId = ModelValidation.requiredText(activeWorkerId, "active worker id");
        activeFence = Objects.requireNonNull(activeFence, "active fence is required");
        expectedParent = Objects.requireNonNull(expectedParent, "expected parent is required");
        sealedManifestDigest = Objects.requireNonNull(sealedManifestDigest, "sealed manifest digest is required");
    }
}
