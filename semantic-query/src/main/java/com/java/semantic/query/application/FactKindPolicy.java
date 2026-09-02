package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactKind;

import java.util.Objects;
import java.util.Set;

/** Centralized accepted fact-kind policy for the transport-neutral query operations. */
public final class FactKindPolicy {
    public static final Set<CodeFactKind> TYPE_MEMBERS = Set.of(CodeFactKind.TYPE);
    public static final Set<CodeFactKind> METHOD_IMPLEMENTATIONS = Set.of(CodeFactKind.METHOD);
    public static final Set<CodeFactKind> CALLERS = Set.of(CodeFactKind.METHOD);
    public static final Set<CodeFactKind> CALLEES = Set.of(CodeFactKind.METHOD);
    public static final Set<CodeFactKind> REFERENCE_TARGETS = Set.of(
            CodeFactKind.TYPE, CodeFactKind.METHOD, CodeFactKind.FIELD,
            CodeFactKind.ENUM_CONSTANT, CodeFactKind.RECORD_COMPONENT,
            CodeFactKind.MAPPER_STATEMENT);

    private FactKindPolicy() {
    }

    public static CodeFactDetails require(CodeFactDetails fact, Set<CodeFactKind> acceptedKinds) {
        CodeFactDetails requiredFact = Objects.requireNonNull(fact, "code fact is required");
        Set<CodeFactKind> requiredKinds = Objects.requireNonNull(acceptedKinds, "accepted kinds are required");
        CodeFactKind kind = requiredFact.fact().identity().kind();
        if (!requiredKinds.contains(kind)) {
            throw new CodeFactKindMismatchException();
        }
        return requiredFact;
    }
}
