package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

public final class RepositoryRelativeSource {

    private RepositoryRelativeSource() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String requireValid(String sourceFile) {
        return ModelValidation.repositoryRelativePath(sourceFile);
    }
}
