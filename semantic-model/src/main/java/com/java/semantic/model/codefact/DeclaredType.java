package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

public record DeclaredType(String canonicalName) {

    public DeclaredType {
        canonicalName = ModelValidation.requiredText(canonicalName, "declared type");
    }
}
