package com.java.semantic.syntax.application.concept;

import com.java.semantic.syntax.domain.TypeReference;

import java.util.Objects;

/** FIELD 概念隨分析版本變動但不參與 identity 的宣告型別證據 */
public record FieldConceptDetails(TypeReference declaredType) {

    public FieldConceptDetails {
        declaredType = Objects.requireNonNull(declaredType, "declaredType is required");
    }
}
