package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import org.springframework.util.Assert;

/** 註解的原始寫法與可選的 JDT 已解析型別識別。 */
public record AnnotationEvidence(String writtenName, Optional<ResolvedTypeIdentity> resolvedType) {

    public AnnotationEvidence {
        Assert.hasText(writtenName, "writtenName is required");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
    }
}
