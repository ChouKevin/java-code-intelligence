package com.java.semantic.semantic.domain;

/** Typed outcome of resolving a descendant call site. */
public enum SemanticCallResolutionStatus {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS
}
