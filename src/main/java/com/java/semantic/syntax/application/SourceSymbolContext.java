package com.java.semantic.syntax.application;

import com.java.semantic.identity.RepositoryRelativeSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** source type 與 optional canonical method 的 resolution context */
public record SourceSymbolContext(
        String fullyQualifiedType,
        Optional<String> sourceFile,
        Optional<MethodContext> method) {

    public SourceSymbolContext {
        fullyQualifiedType = requiredText(fullyQualifiedType, "fullyQualifiedType");
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required")
                .map(RepositoryRelativeSource::requireValid);
        method = Objects.requireNonNull(method, "method is required");
    }

    /** method name 與 optional canonical parameter type selector */
    public record MethodContext(String name, Optional<List<String>> parameterTypes) {

        public MethodContext {
            name = requiredText(name, "name");
            parameterTypes = Objects.requireNonNull(parameterTypes, "parameterTypes is required")
                    .map(types -> List.copyOf(Objects.requireNonNull(types, "parameterTypes values are required")));
            parameterTypes.ifPresent(types -> types.forEach(type -> requiredText(type, "parameterType")));
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }
}
