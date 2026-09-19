package com.java.semantic.model.review;

import com.java.semantic.model.support.ModelValidation;

/** Opaque identifier for one immutable current-to-commit review. */
public record ReviewId(String value) {

    public ReviewId {
        value = ModelValidation.requiredText(value, "review id");
    }
}
