package com.java.semantic.semantic.domain;

import com.java.semantic.identity.RepositoryRelativeSource;

import java.util.Objects;

/** JDT LS reference 查詢使用的精確宣告識別字位置 */
public record SemanticReferenceAnchor(String sourceFile, SemanticPosition identifierPosition) {

    public SemanticReferenceAnchor {
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        Objects.requireNonNull(identifierPosition, "identifierPosition is required");
    }
}
