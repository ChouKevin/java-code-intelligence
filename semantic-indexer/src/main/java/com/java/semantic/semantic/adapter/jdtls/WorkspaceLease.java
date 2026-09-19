package com.java.semantic.semantic.adapter.jdtls;

/** Owns exactly one live JDT LS session and its contained data directory. */
public interface WorkspaceLease extends AutoCloseable {
    JdtWorkspaceSession session();

    @Override
    void close();
}
