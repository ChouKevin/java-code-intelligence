package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record SourceTypeIdentity(JavaTypeIdentity javaType, String sourceFile) implements CanonicalIdentity {

    public SourceTypeIdentity {
        javaType = Objects.requireNonNull(javaType, "java type is required");
        sourceFile = ModelValidation.repositoryRelativePath(sourceFile);
    }

    @Override
    public String canonicalForm() {
        return javaType.canonicalForm() + "@" + sourceFile;
    }

    public String fullyQualifiedName() {
        return javaType.fullyQualifiedName();
    }
}
