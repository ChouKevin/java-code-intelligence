package com.java.semantic.callgraph.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExplainableCallGraph(
        MethodId root,
        List<CallNode> nodes,
        List<CallEdge> edges,
        Map<String, String> relatedClasses,
        FlattenedCallGraph legacyFlattened) {

    public ExplainableCallGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        relatedClasses = Collections.unmodifiableMap(new LinkedHashMap<>(relatedClasses));
    }
}
