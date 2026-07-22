package com.java.semantic.callgraph.domain;

/** Whether a node includes a repository method declaration. */
public enum NodeContentState {
    FULL_SOURCE,
    TARGET_ONLY,
    EXTERNAL
}
