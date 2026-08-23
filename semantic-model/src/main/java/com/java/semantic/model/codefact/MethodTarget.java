package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;

public record MethodTarget(SourceTypeIdentity sourceType, String methodName, List<String> parameterTypes)
        implements CanonicalIdentity {

    public MethodTarget {
        sourceType = Objects.requireNonNull(sourceType, "source type is required");
        methodName = ModelValidation.requiredText(methodName, "method name");
        ModelValidation.require(methodName.length() <= 255, "method name must not exceed 255 characters");
        ModelValidation.require(Character.isJavaIdentifierStart(methodName.codePointAt(0))
                        && methodName.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)
                        && methodName.codePoints().noneMatch(Character::isIdentifierIgnorable),
                "method name must be a Java identifier");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameter types are required"));
        for (String parameterType : parameterTypes) {
            ModelValidation.requiredText(parameterType, "parameter type");
        }
    }

    public String sourceFile() {
        return sourceType.sourceFile();
    }

    public String packageName() {
        return sourceType.javaType().packageName();
    }

    public String className() {
        return sourceType.javaType().className();
    }

    public String fullyQualifiedClassName() {
        return sourceType.fullyQualifiedName();
    }

    @Override
    public String canonicalForm() {
        String parameters = parameterTypes.stream().reduce((left, right) -> left + "," + right)
                .orElse("");
        return sourceType.canonicalForm() + "#" + methodName + "(" + parameters + ")";
    }
}
