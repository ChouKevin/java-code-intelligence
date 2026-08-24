package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

/** Current-generation listener discovery over indexed method declarations. */
public record EventListenerQuery(RepositoryId repositoryId, RepositoryRevision revision, String eventType, int offset, int limit) {

    public EventListenerQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        eventType = ModelValidation.requiredText(eventType, "event type");
        ModelValidation.require(offset >= 0, "offset must not be negative");
        ModelValidation.require(limit >= 1 && limit <= 100, "limit must be between 1 and 100");
    }
}
