package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 一個 mapper `<sql>` fragment 的未求值原始證據 */
public record MapperFragmentEvidence(MapperFragmentIdentity identity, String content) {

    public MapperFragmentEvidence {
        identity = Objects.requireNonNull(identity, "identity is required");
        content = Objects.requireNonNull(content, "content is required");
    }
}
