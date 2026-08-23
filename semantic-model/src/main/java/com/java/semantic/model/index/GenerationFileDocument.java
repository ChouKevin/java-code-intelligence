package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record GenerationFileDocument(
        RepositoryId repositoryId,
        GenerationId generationId,
        String sourcePath,
        SourceArtifactId sourceArtifactId,
        String contentHash) {

    public GenerationFileDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        sourcePath = ModelValidation.repositoryRelativePath(sourcePath);
        sourceArtifactId = Objects.requireNonNull(sourceArtifactId, "source artifact id is required");
        contentHash = ModelValidation.sha256(contentHash, "content hash");
        ModelValidation.require(sourceArtifactId.value().equals(contentHash), "source artifact id must equal content hash");
    }
}
