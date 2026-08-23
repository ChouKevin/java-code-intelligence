package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import java.util.List;
import java.util.Objects;

/** JDT binding 已證實的呼叫目標識別；lambda 不具此目標。 */
public record InvocationTarget(String packageName, String className, String methodName, List<String> parameterTypes) {

    public InvocationTarget {
        require(!Objects.isNull(packageName), "packageName is required");
        require(hasText(className), "className is required");
        require(hasText(methodName), "methodName is required");
        className = JavaIdentityNormalizer.className(packageName, className);
        parameterTypes = JavaIdentityNormalizer.parameterTypes(
                Objects.requireNonNull(parameterTypes, "parameterTypes is required"));
    }

    private static boolean hasText(String value) {
        return !Objects.requireNonNullElse(value, "").isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
