package com.java.semantic.syntax.domain;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

/**
 * 一個呼叫位置的原始語法證據。
 * writtenName 來自 AST 的呼叫名稱；僅缺少語法證據的 call hierarchy fallback 使用空字串。
 */
public record SyntaxInvocation(
        InvocationKind kind,
        SyntaxRange range,
        String expression,
        String writtenName,
        String receiver,
        String receiverDeclaration,
        String qualifier,
        Optional<InvocationTarget> resolvedTarget,
        SyntaxPosition resolutionAnchor,
        List<SyntaxInvocationArgument> arguments) {

    public SyntaxInvocation {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(range, "range is required");
        require(hasText(expression), "expression is required");
        writtenName = Objects.requireNonNull(writtenName, "written name is required");
        receiver = Objects.requireNonNullElse(receiver, "");
        receiverDeclaration = Objects.requireNonNullElse(receiverDeclaration, "");
        qualifier = Objects.requireNonNullElse(qualifier, "");
        resolvedTarget = Objects.requireNonNull(resolvedTarget, "resolvedTarget is required");
        resolutionAnchor = Objects.requireNonNull(resolutionAnchor, "resolutionAnchor is required");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments are required"));
    }

    /** JDT Core 能直接辨識的呼叫語法種類 */
    public enum InvocationKind {
        METHOD,
        CONSTRUCTOR,
        LAMBDA,
        METHOD_REFERENCE,
        STATIC_IMPORT
    }

    private static boolean hasText(String value) {
        return !Objects.requireNonNullElse(value, "").isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
