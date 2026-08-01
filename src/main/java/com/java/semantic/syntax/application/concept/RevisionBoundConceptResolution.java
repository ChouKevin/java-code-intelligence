package com.java.semantic.syntax.application.concept;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.util.Objects;

/** 綁定實際分析 revision 的單一精確 concept catalog entry */
public record RevisionBoundConceptResolution(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        ConceptCatalogEntry candidate) {

    public RevisionBoundConceptResolution {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        candidate = Objects.requireNonNull(candidate, "candidate is required");
    }
}
