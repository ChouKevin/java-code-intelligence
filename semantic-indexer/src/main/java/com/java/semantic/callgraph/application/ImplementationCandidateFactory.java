package com.java.semantic.callgraph.application;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.syntax.domain.SourceTypeMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 由已證實的類別 metadata 建立 implementation candidate */
public final class ImplementationCandidateFactory {

    public Optional<ImplementationCandidate> create(
            RepositorySyntaxIndex index,
            SemanticMethod method,
            MethodTarget target) {
        Objects.requireNonNull(index, "index is required");
        Objects.requireNonNull(method, "method is required");
        Objects.requireNonNull(target, "target is required");
        return index.sourceType(target).map(metadata -> candidate(method, target, metadata));
    }

    private ImplementationCandidate candidate(SemanticMethod method, MethodTarget target, SourceTypeMetadata metadata) {
        return new ImplementationCandidate(
                method,
                target,
                metadata.frameworkFacts().primary(),
                metadata.frameworkFacts().beanQualifiers(),
                metadata.frameworkFacts().profiles());
    }
}
