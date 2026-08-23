package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.RepositoryRelativeSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 來源型別與可選 canonical 方法的解析查詢條件 */
public record SourceSymbolContext(
        JavaTypeIdentity javaType,
        Optional<String> sourceFile,
        Optional<MethodContext> method) {

    public SourceSymbolContext {
        javaType = Objects.requireNonNull(javaType, "javaType is required");
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required")
                .map(RepositoryRelativeSource::requireValid);
        method = Objects.requireNonNull(method, "method is required");
    }

    /** 方法名稱與可選 canonical 參數型別的查詢條件 */
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
