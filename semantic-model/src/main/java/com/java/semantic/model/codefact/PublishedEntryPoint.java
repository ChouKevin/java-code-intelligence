package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

/** Current-generation entry-point projection with code-derived route trigger fields. */
public record PublishedEntryPoint(CurrentGeneration generation, String factId, String canonicalIdentity, EntryPointKind kind,
                                  String methodCanonical, String triggerValue, String sourcePath) {
    public PublishedEntryPoint {
        generation = Objects.requireNonNull(generation, "generation is required");
        factId = ModelValidation.requiredText(factId, "fact id");
        canonicalIdentity = ModelValidation.requiredText(canonicalIdentity, "canonical identity");
        kind = Objects.requireNonNull(kind, "entry point kind is required");
        methodCanonical = ModelValidation.requiredText(methodCanonical, "method canonical");
        triggerValue = ModelValidation.requiredText(triggerValue, "trigger value");
        sourcePath = ModelValidation.repositoryRelativePath(sourcePath);
    }
}
