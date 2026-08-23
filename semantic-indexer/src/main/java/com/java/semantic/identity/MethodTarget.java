package com.java.semantic.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 已由來源檔案限定的 canonical 方法識別 */
public record MethodTarget(
        SourceTypeIdentity sourceType,
        String methodName,
        List<String> parameterTypes) {

    private static final int MAX_IDENTIFIER_LENGTH = 255;

    public MethodTarget {
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        assertMethodName(methodName);
        parameterTypes = validatedParameterTypes(parameterTypes);
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

    private static void assertMethodName(String methodName) {
        require(!Objects.requireNonNull(methodName, "methodName is required").isBlank(),
                "methodName must not be blank");
        require(methodName.length() <= MAX_IDENTIFIER_LENGTH, "methodName must not exceed 255 characters");
        require(isJavaIdentifier(methodName), "methodName must be a Java identifier");
    }

    private static boolean isJavaIdentifier(String identifier) {
        return !identifier.isBlank()
                && Character.isJavaIdentifierStart(identifier.codePointAt(0))
                && identifier.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)
                && identifier.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static List<String> validatedParameterTypes(List<String> parameterTypes) {
        Objects.requireNonNull(parameterTypes, "parameterTypes are required");
        List<String> copy = new ArrayList<>(parameterTypes);
        for (String parameterType : copy) {
            require(!Objects.requireNonNull(parameterType, "parameterTypes must not contain null values").isBlank(),
                    "parameterTypes must not contain blank values");
        }
        return List.copyOf(copy);
    }
}
