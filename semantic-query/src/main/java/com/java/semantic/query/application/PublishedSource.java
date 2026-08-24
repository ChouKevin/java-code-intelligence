package com.java.semantic.query.application;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record PublishedSource(CurrentGeneration generation, String sourcePath, String utf8Content) {
    public PublishedSource {
        generation = Objects.requireNonNull(generation, "current generation is required");
        sourcePath = ModelValidation.requiredText(sourcePath, "source path");
        utf8Content = Objects.requireNonNull(utf8Content, "source content is required");
    }
}
