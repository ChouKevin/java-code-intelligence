package com.java.semantic.model.index;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record EntryPointDocument(
        RepositoryId repositoryId,
        GenerationId generationId,
        CodeFact fact,
        EntryPointKind kind,
        MethodTarget method,
        EntryPointTrigger trigger,
        SourceRange range) {

    public EntryPointDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        fact = Objects.requireNonNull(fact, "code fact is required");
        kind = Objects.requireNonNull(kind, "entry point kind is required");
        method = Objects.requireNonNull(method, "method is required");
        trigger = Objects.requireNonNull(trigger, "trigger is required");
        range = Objects.requireNonNull(range, "source range is required");
        ModelValidation.require(repositoryId.equals(fact.identity().repositoryId()),
                "entry point repository must match fact");
        ModelValidation.require(fact.identity().canonicalIdentity().equals(new EntryPointIdentity(kind, method, trigger)),
                "entry point fact identity must describe the entry point document");
        ModelValidation.require(fact.identity().kind() == expectedFactKind(kind),
                "entry point fact kind must match entry point kind");
        boolean http = trigger.httpMethod().isPresent() && trigger.httpPath().isPresent()
                && trigger.destination().isEmpty() && trigger.schedule().isEmpty();
        boolean mq = trigger.httpMethod().isEmpty() && trigger.httpPath().isEmpty()
                && trigger.destination().isPresent() && trigger.schedule().isEmpty();
        boolean schedule = trigger.httpMethod().isEmpty() && trigger.httpPath().isEmpty()
                && trigger.destination().isEmpty() && trigger.schedule().isPresent();
        ModelValidation.require((kind == EntryPointKind.HTTP && http)
                        || (kind == EntryPointKind.MQ && mq)
                        || (kind == EntryPointKind.SCHEDULE && schedule),
                "entry point trigger must match entry point kind");
    }

    private static CodeFactKind expectedFactKind(EntryPointKind kind) {
        return switch (kind) {
            case HTTP -> CodeFactKind.API_ROUTE;
            case MQ -> CodeFactKind.MQ_DESTINATION;
            case SCHEDULE -> CodeFactKind.SCHEDULE;
        };
    }
}
