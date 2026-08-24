package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.SyntaxRange;
import java.util.Objects;
import java.util.Optional;

/** An AST-backed invocation argument, preserving the literal value only when Java syntax proves it. */
public record SyntaxInvocationArgument(
        SyntaxRange range,
        String expression,
        Optional<String> literalValue) {

    public SyntaxInvocationArgument {
        range = Objects.requireNonNull(range, "range is required");
        expression = Objects.requireNonNullElse(expression, "");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }
        literalValue = Objects.requireNonNull(literalValue, "literalValue is required");
    }
}
