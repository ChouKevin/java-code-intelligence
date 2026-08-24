package com.java.semantic.model.codefact;

import java.util.Objects;

/** Derived search result: identity and source location only, never narrative interpretation. */
public record CodeFactSummary(CodeFact fact, SourceRange location) {

    public CodeFactSummary {
        fact = Objects.requireNonNull(fact, "code fact is required");
        location = Objects.requireNonNull(location, "location is required");
    }
}
