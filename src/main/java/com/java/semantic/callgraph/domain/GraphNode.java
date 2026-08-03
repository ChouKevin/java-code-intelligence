package com.java.semantic.callgraph.domain;

import com.java.semantic.identity.MethodTarget;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

/** One response-local outgoing graph node with explicit source and traversal state. */
public record GraphNode(
        CallNodeId nodeId,
        Optional<MethodTarget> target,
        String externalSymbol,
        NodeContentState contentState,
        NodeTraversalState traversalState,
        DispatchKind dispatchKind,
        Optional<CallSiteRange> declarationRange) {

    public GraphNode {
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        Assert.hasText(nodeId.value(), "nodeId must not be blank");
        target = Objects.requireNonNull(target, "target is required");
        externalSymbol = Objects.requireNonNullElse(externalSymbol, "");
        contentState = Objects.requireNonNull(contentState, "contentState is required");
        traversalState = Objects.requireNonNull(traversalState, "traversalState is required");
        dispatchKind = Objects.requireNonNull(dispatchKind, "dispatchKind is required");
        declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
        switch (contentState) {
            case FULL_SOURCE -> {
                Assert.isTrue(target.isPresent(), "full source node target is required");
                Assert.isTrue(declarationRange.isPresent(), "full source node declaration range is required");
                Assert.isTrue(!StringUtils.hasText(externalSymbol), "full source node external symbol is forbidden");
                Assert.isTrue(NodeTraversalState.EXPANDED.equals(traversalState)
                                || NodeTraversalState.DEPTH_BOUNDARY.equals(traversalState),
                        "full source node traversal state is invalid");
            }
            case TARGET_ONLY -> {
                Assert.isTrue(target.isPresent(), "target-only node target is required");
                Assert.isTrue(declarationRange.isEmpty(), "target-only node range is forbidden");
                Assert.isTrue(!StringUtils.hasText(externalSymbol), "target-only node external symbol is forbidden");
                Assert.isTrue(NodeTraversalState.BUDGET_CUTOFF.equals(traversalState)
                                || NodeTraversalState.OPAQUE.equals(traversalState),
                        "target-only node must be budget cutoff or opaque");
            }
            case EXTERNAL -> {
                Assert.isTrue(target.isEmpty(), "external node target is forbidden");
                Assert.isTrue(declarationRange.isEmpty(), "external node range is forbidden");
                Assert.hasText(externalSymbol, "external node symbol is required");
                Assert.isTrue(NodeTraversalState.EXTERNAL.equals(traversalState)
                                || NodeTraversalState.OPAQUE.equals(traversalState),
                        "external node must be external or opaque traversal state");
                Assert.isTrue(DispatchKind.SYNCHRONOUS.equals(dispatchKind),
                        "external node dispatch kind must be synchronous");
            }
        }
    }
}
