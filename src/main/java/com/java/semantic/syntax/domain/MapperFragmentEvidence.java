package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 一個 mapper `<sql>` fragment 的未求值原始證據 */
public record MapperFragmentEvidence(MapperFragmentIdentity identity, SourceRange location) {

    public MapperFragmentEvidence {
        identity = Objects.requireNonNull(identity, "identity is required");
        location = Objects.requireNonNull(location, "location is required");
        if (!identity.resourcePath().equals(location.sourceFile())) {
            throw new IllegalArgumentException("location sourceFile must match mapper fragment identity");
        }
    }
}
