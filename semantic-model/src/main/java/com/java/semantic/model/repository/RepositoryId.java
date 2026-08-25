package com.java.semantic.model.repository;

import com.java.semantic.model.support.ModelValidation;

import java.util.regex.Pattern;

public record RepositoryId(String value) {

    public static final String PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";
    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 64;
    private static final Pattern SAFE = Pattern.compile(PATTERN);

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
