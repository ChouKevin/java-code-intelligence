package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.model.codefact.MethodTarget;

import java.util.LinkedHashMap;
import java.util.Map;

/** Response-local deterministic node identifier allocation for one directional graph fragment. */
final class CallTraversalState {

    private final Map<MethodTarget, CallNodeId> localNodeIds = new LinkedHashMap<>();
    private final Map<String, CallNodeId> externalNodeIds = new LinkedHashMap<>();

    CallNodeId localNodeId(MethodTarget target) {
        return localNodeIds.computeIfAbsent(target, ignored -> nextId());
    }

    CallNodeId externalNodeId(String externalSymbol) {
        return externalNodeIds.computeIfAbsent(externalSymbol, ignored -> nextId());
    }

    private CallNodeId nextId() {
        int next = localNodeIds.size() + externalNodeIds.size();
        return new CallNodeId(String.format("node-%04d", next));
    }
}
