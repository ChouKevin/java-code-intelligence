package com.java.semantic.callgraph.domain;

/** How far the service traversed a node in one response-local fragment. */
public enum NodeTraversalState {
    EXPANDED,
    DEPTH_BOUNDARY,
    BUDGET_CUTOFF,
    EXTERNAL
}
