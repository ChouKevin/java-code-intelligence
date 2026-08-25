package com.java.semantic.indexer.incremental;

/** Git tree change kinds understood by incremental planning. */
public enum ChangeKind {
    ADD,
    MODIFY,
    DELETE,
    RENAME
}
