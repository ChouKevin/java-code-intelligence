package com.java.semantic.callgraph.domain;

import com.java.semantic.identity.PolicyIdentity;

import java.util.List;

public record MethodId(
        String repoId,
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes) {

    public MethodId {
        className = PolicyIdentity.className(packageName, className);
        parameterTypes = PolicyIdentity.parameterTypes(parameterTypes);
    }
}
