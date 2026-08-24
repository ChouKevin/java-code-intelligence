package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

/** A persisted, typed extraction problem for one indexed repository source. */
public record SourceIndexIssue(String sourcePath, String code) {
    public SourceIndexIssue {
        sourcePath = ModelValidation.repositoryRelativePath(sourcePath);
        code = ModelValidation.requiredText(Objects.requireNonNull(code, "issue code is required"), "issue code");
    }
}
