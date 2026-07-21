package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.EntryPointClass;

import java.util.List;
import java.util.Objects;

public record RevisionBoundEntryPoints(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        List<EntryPointClass> entryPoints) {

    public RevisionBoundEntryPoints {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        entryPoints = List.copyOf(entryPoints);
    }
}
