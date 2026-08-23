package com.java.semantic.model.repository;

import com.java.semantic.model.support.ModelValidation;

import java.util.regex.Pattern;

public record RepositoryId(String value) {

    private static final Pattern SAFE = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    public RepositoryId {
        try {
            value = ModelValidation.requiredText(value, "repository id");
        } catch (RuntimeException exception) {
            throw new InvalidRepositoryIdException(value);
        }
        if (!SAFE.matcher(value).matches()) {
            throw new InvalidRepositoryIdException(value);
        }
    }

    public static RepositoryId of(String value) {
        return new RepositoryId(value);
    }
}
