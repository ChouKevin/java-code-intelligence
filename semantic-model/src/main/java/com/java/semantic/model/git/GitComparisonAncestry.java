package com.java.semantic.model.git;

/** Relationship of the exact requested commits; it is never inferred from a merge base substitution. */
public enum GitComparisonAncestry {
    SAME, PREVIOUS_ANCESTOR, CURRENT_ANCESTOR, DIVERGED
}
