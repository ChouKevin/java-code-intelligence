package com.java.semantic.model.codefact;

import java.util.Objects;

public record CodeFact(CodeFactId id, CodeFactIdentity identity) {

    public CodeFact {
        id = Objects.requireNonNull(id, "code fact id is required");
        identity = Objects.requireNonNull(identity, "code fact identity is required");
        if (!id.equals(CodeFactId.from(identity))) {
            throw new IllegalArgumentException("code fact id must match code fact identity");
        }
    }
}
