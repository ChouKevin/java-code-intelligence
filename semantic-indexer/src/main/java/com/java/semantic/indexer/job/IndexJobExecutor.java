package com.java.semantic.indexer.job;

import com.java.semantic.indexer.source.RepositorySourceManager;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** The sole owner of running source preparation's terminal transition. */
@Component
public final class IndexJobExecutor {
    private final RepositorySourceManager manager;

    public IndexJobExecutor(RepositorySourceManager manager) {
        this.manager = Objects.requireNonNull(manager);
    }

    public SourcePreparationJob execute(SourcePreparationJob job) {
        return manager.execute(job);
    }
}
