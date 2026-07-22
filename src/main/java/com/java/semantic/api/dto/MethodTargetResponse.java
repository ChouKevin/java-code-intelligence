package com.java.semantic.api.dto;

import java.util.List;

/** API representation of a source-qualified canonical method target. */
public record MethodTargetResponse(
        String sourceFile,
        String packageName,
        String className,
        String methodName,
        List<String> parameterTypes) {

    public MethodTargetResponse {
        parameterTypes = List.copyOf(parameterTypes);
    }
}
