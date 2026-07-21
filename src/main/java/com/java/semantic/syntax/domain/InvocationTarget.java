package com.java.semantic.syntax.domain;

import com.java.semantic.identity.PolicyIdentity;
import java.util.List;
import java.util.Objects;

import org.springframework.util.Assert;

/** JDT binding 已證實的呼叫目標識別；lambda 不具此目標。 */
public record InvocationTarget(String packageName, String className, String methodName, List<String> parameterTypes) {

    public InvocationTarget {
        Assert.notNull(packageName, "packageName is required");
        Assert.hasText(className, "className is required");
        Assert.hasText(methodName, "methodName is required");
        className = PolicyIdentity.className(packageName, className);
        parameterTypes = PolicyIdentity.parameterTypes(
                Objects.requireNonNull(parameterTypes, "parameterTypes is required"));
    }
}
