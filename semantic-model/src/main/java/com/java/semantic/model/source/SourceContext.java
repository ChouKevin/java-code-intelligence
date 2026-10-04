package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

public record SourceContext(String repositoryId, String revision) {

    public SourceContext {
        repositoryId = new RepositoryId(repositoryId).value();
        revision = RepositoryRevision.ofSha(revision).value();
    }
}
