package com.java.semantic.callgraph.domain;

/** How far the service traversed a node in one response-local fragment. */
public enum NodeTraversalState {
    EXPANDED,
    DEPTH_BOUNDARY,
    BUDGET_CUTOFF,
    /** 證據判定為終端的節點，不可展開也不可 re-root */
    OPAQUE,
    EXTERNAL
}
