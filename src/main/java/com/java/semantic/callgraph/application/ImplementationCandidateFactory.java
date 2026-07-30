package com.java.semantic.callgraph.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.syntax.domain.ClassMetadata;

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
        return index.classMetadata(target).map(metadata -> candidate(method, target, metadata));
    }

    private ImplementationCandidate candidate(SemanticMethod method, MethodTarget target, ClassMetadata metadata) {
        return new ImplementationCandidate(
                method,
                target,
                metadata.primary(),
                metadata.beanQualifiers(),
                metadata.profiles());
    }
}
