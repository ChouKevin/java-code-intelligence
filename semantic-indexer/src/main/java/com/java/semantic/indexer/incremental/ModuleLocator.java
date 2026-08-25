package com.java.semantic.indexer.incremental;

import java.util.Optional;
import java.util.Set;

/** Revision-indexed build ownership and dependency graph boundary. */
public interface ModuleLocator {
    Optional<String> locate(String path);

    Optional<Set<String>> reverseDependencyClosure(String module);

    Optional<Set<String>> supportedSources(String module);
}
