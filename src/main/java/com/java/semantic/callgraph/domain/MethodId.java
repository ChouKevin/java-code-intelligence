package com.java.semantic.callgraph.domain;

import com.java.semantic.identity.JavaIdentityNormalizer;

import java.util.List;

public record MethodId(
        String repoId,
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes) {

    public MethodId {
        className = JavaIdentityNormalizer.className(packageName, className);
        parameterTypes = JavaIdentityNormalizer.parameterTypes(parameterTypes);
    }
}
