package com.java.semantic.model.repository;

import com.java.semantic.model.support.ModelValidation;

import java.util.regex.Pattern;

public record RepositoryRevision(String value) {

    public static final String PATTERN = "^[0-9a-f]{40}$";
    public static final int LENGTH = 40;
    private static final Pattern SHA = Pattern.compile(PATTERN);

    public RepositoryRevision {
        value = ModelValidation.requiredText(value, "repository revision");
        if (!SHA.matcher(value).matches()) {
            throw new IllegalArgumentException("repository revision must be a 40-character lowercase hexadecimal value");
        }
    }

    public static RepositoryRevision ofSha(String value) {
        return new RepositoryRevision(value);
    }
}
