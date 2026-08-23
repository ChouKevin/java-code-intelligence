package com.java.semantic.syntax.domain;
import com.java.semantic.model.codefact.SyntaxRange;

import java.util.Objects;

/** 精確宣告完整範圍與識別符錨點 */
public record ExactSourceDeclaration(
        ExactSourceDeclarationTarget target,
        SyntaxRange declarationRange,
        SyntaxRange identifierRange) {

    public ExactSourceDeclaration {
        target = Objects.requireNonNull(target, "target is required");
        declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
        identifierRange = Objects.requireNonNull(identifierRange, "identifierRange is required");
    }
}
