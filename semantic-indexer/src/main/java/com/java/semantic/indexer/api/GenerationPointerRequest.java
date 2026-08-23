package com.java.semantic.indexer.api;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryRevision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Exact persisted pointer supplied by the administrator for rollback CAS. */
public record GenerationPointerRequest(@NotBlank String revision, @NotBlank String generationId,
                                       @NotBlank String manifestDigest, @NotBlank String committedJobId,
                                       @NotNull Instant publishedAt) {
    PublishedGenerationPointer toPointer() {
        return new PublishedGenerationPointer(new RepositoryRevision(revision), new GenerationId(generationId),
                new ManifestDigest(manifestDigest), committedJobId, publishedAt);
    }
}
