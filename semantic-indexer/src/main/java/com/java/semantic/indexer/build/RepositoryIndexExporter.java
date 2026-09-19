package com.java.semantic.indexer.build;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.util.List;

/** Production boundary for a deterministic first-generation export. */
public interface RepositoryIndexExporter {
    List<SourceIndexBatch> export(RepositoryId repositoryId, RepositoryRevision revision,
                                  GenerationId generationId, FullIndexPlan plan);

    /** A prepared build supplies the semantic service bound to its own live analysis lease. */
    default List<SourceIndexBatch> export(RepositoryId repositoryId, RepositoryRevision revision,
                                          GenerationId generationId, FullIndexPlan plan,
                                          JavaSemanticService semanticService) {
        return export(repositoryId, revision, generationId, plan);
    }
}
