package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactKind;
import java.util.Objects;
import java.util.Set;

/** Kind admission for projected navigation, not extraction completeness. */
public final class FactKindPolicy {
    public static final Set<CodeFactKind> IMPLEMENTATIONS = Set.of(CodeFactKind.TYPE, CodeFactKind.METHOD);
    public static final Set<CodeFactKind> CALLS = Set.of(CodeFactKind.METHOD);
    public static final Set<CodeFactKind> REFERENCE_TARGETS = Set.of(CodeFactKind.TYPE, CodeFactKind.METHOD, CodeFactKind.FIELD,
            CodeFactKind.ENUM_CONSTANT, CodeFactKind.RECORD_COMPONENT, CodeFactKind.MAPPER_STATEMENT);
    private FactKindPolicy() { }

    public static CodeFactDetails require(CodeFactDetails fact, Set<CodeFactKind> acceptedKinds) {
        Objects.requireNonNull(fact);
        if (!acceptedKinds.contains(fact.fact().identity().kind())) throw new CodeFactKindMismatchException();
        return fact;
    }
}
