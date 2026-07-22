package com.java.semantic.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Canonical method identity qualified by the source file that declares it. */
public record MethodTarget(
        String sourceFile,
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes) {

    private static final int MAX_IDENTIFIER_LENGTH = 255;

    public MethodTarget {
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        packageName = Objects.requireNonNull(packageName, "packageName is required");
        assertQualifiedClassName(className);
        assertMethodName(methodName);
        parameterTypes = validatedParameterTypes(parameterTypes);
    }

    private static void assertQualifiedClassName(String className) {
        Assert.isTrue(StringUtils.hasText(className), "className must not be blank");
        Assert.isTrue(className.length() <= MAX_IDENTIFIER_LENGTH, "className must not exceed 255 characters");
        String[] classNameSegments = className.split("\\.", -1);
        for (String classNameSegment : classNameSegments) {
            Assert.isTrue(isJavaIdentifier(classNameSegment), "className must be dot-separated Java identifiers");
        }
    }

    private static void assertMethodName(String methodName) {
        Assert.isTrue(StringUtils.hasText(methodName), "methodName must not be blank");
        Assert.isTrue(methodName.length() <= MAX_IDENTIFIER_LENGTH, "methodName must not exceed 255 characters");
        Assert.isTrue(isJavaIdentifier(methodName), "methodName must be a Java identifier");
    }

    private static boolean isJavaIdentifier(String identifier) {
        return StringUtils.hasText(identifier)
                && Character.isJavaIdentifierStart(identifier.codePointAt(0))
                && identifier.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)
                && identifier.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    private static List<String> validatedParameterTypes(List<String> parameterTypes) {
        Objects.requireNonNull(parameterTypes, "parameterTypes are required");
        List<String> copy = new ArrayList<>(parameterTypes);
        for (String parameterType : copy) {
            Assert.isTrue(StringUtils.hasText(parameterType), "parameterTypes must not contain blank values");
        }
        return List.copyOf(copy);
    }
}
