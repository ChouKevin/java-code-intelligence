package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

import java.util.regex.Pattern;

public record GenerationId(String value) {

    private static final Pattern SAFE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    public GenerationId {
        value = ModelValidation.requiredText(value, "generation id");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("generation id is invalid");
        }
    }
}
