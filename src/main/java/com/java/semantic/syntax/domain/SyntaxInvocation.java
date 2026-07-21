package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import org.springframework.util.Assert;

/** 一個呼叫位置的原始語法證據 */
public record SyntaxInvocation(
        InvocationKind kind,
        SyntaxRange range,
        String expression,
        String receiver,
        String receiverDeclaration,
        String qualifier,
        Optional<InvocationTarget> resolvedTarget,
        SyntaxPosition resolutionAnchor) {

    public SyntaxInvocation {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(range, "range is required");
        Assert.hasText(expression, "expression is required");
        receiver = Objects.requireNonNullElse(receiver, "");
        receiverDeclaration = Objects.requireNonNullElse(receiverDeclaration, "");
        qualifier = Objects.requireNonNullElse(qualifier, "");
        resolvedTarget = Objects.requireNonNull(resolvedTarget, "resolvedTarget is required");
        resolutionAnchor = Objects.requireNonNull(resolutionAnchor, "resolutionAnchor is required");
    }

    /** JDT Core 能直接辨識的呼叫語法種類 */
    public enum InvocationKind {
        METHOD,
        CONSTRUCTOR,
        LAMBDA,
        METHOD_REFERENCE,
        STATIC_IMPORT
    }
}
