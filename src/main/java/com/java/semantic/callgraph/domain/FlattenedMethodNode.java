package com.java.semantic.callgraph.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FlattenedMethodNode(
        String signature,
        String className,
        String methodName,
        CallType callType,
        String description,
        String code,
        Map<String, String> annotations,
        List<String> callees) {

    public FlattenedMethodNode {
        annotations = Collections.unmodifiableMap(new LinkedHashMap<>(annotations));
        callees = List.copyOf(callees);
    }
}
