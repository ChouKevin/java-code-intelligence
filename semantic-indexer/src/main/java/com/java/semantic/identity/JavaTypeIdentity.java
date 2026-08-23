package com.java.semantic.identity;

import java.util.Objects;

/** 已正規化的 Java 宣告型別識別 */
public record JavaTypeIdentity(String packageName, String className) {

    private static final int MAX_IDENTIFIER_LENGTH = 255;

    public JavaTypeIdentity {
        packageName = Objects.requireNonNull(packageName, "packageName is required").trim();
        className = JavaIdentityNormalizer.className(packageName, className);
        assertQualifiedClassName(className);
    }

    public String fullyQualifiedName() {
        return packageName.isBlank() ? className : packageName + "." + className;
    }

    private static void assertQualifiedClassName(String className) {
        require(!className.isBlank(), "className must not be blank");
        require(className.length() <= MAX_IDENTIFIER_LENGTH, "className must not exceed 255 characters");
        String[] classNameSegments = className.split("\\.", -1);
        for (String classNameSegment : classNameSegments) {
            require(isJavaIdentifier(classNameSegment), "className must be dot-separated Java identifiers");
        }
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
}
