package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.MethodTarget;

import java.util.Objects;
/** overload ambiguity 的 canonical method selector evidence */
public record SourceMethodContextCandidate(MethodTarget target) implements SourceContextCandidate {

    public SourceMethodContextCandidate {
        target = Objects.requireNonNull(target, "target is required");
    }

    @Override
    public SourceSymbolKind kind() {
        return SourceSymbolKind.METHOD;
    }
}
