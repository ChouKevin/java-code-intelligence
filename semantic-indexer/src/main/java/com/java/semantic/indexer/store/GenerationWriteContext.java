package com.java.semantic.indexer.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

/** The durable job that is permitted to write one immutable generation. */
public record GenerationWriteContext(RepositoryId repositoryId, GenerationId generationId, String jobId) {
    public GenerationWriteContext {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        jobId = ModelValidation.requiredText(jobId, "job id");
    }
}
