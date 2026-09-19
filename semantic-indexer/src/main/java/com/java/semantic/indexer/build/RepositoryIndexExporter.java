package com.java.semantic.indexer.build;

import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.store.GenerationWriteContext;

/** Production boundary for a deterministic, lease-bound semantic export. */
public interface RepositoryIndexExporter {
    RepositoryIndexExport export(GenerationWriteContext context, PreparedAnalysis analysis);
}
