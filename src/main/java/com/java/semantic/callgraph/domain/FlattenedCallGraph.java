package com.java.semantic.callgraph.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FlattenedCallGraph(
        List<FlattenedMethodNode> methods,
        String rootSignature,
        Map<String, String> relatedClasses) {

    public FlattenedCallGraph {
        methods = List.copyOf(methods);
        relatedClasses = Collections.unmodifiableMap(new LinkedHashMap<>(relatedClasses));
    }
}
