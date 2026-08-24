package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.MongoGenerationWriter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Production bridge from projection batches to the immutable generation writer. */
public final class MongoIndexBatchWriter implements IndexBatchWriter {
    private final MongoGenerationWriter generationWriter;
    private final MongoGenerationWriter.GenerationLease lease;
    private final SourceIndexBatchDocumentMapper documentMapper;
    private final Set<String> writtenArtifacts = ConcurrentHashMap.newKeySet();
    private final Set<String> writtenGenerationFiles = ConcurrentHashMap.newKeySet();

    public MongoIndexBatchWriter(MongoGenerationWriter generationWriter, MongoGenerationWriter.GenerationLease lease,
                                 SourceIndexBatchDocumentMapper documentMapper) {
        this.generationWriter = Objects.requireNonNull(generationWriter, "generation writer is required");
        this.lease = Objects.requireNonNull(lease, "generation lease is required");
        this.documentMapper = Objects.requireNonNull(documentMapper, "document mapper is required");
    }

    @Override
    public void write(SourceIndexBatch batch) {
        Objects.requireNonNull(batch, "source batch is required");
        if (!lease.repositoryId().equals(batch.repositoryId()) || !lease.generationId().equals(batch.generationId())) {
            throw new IllegalArgumentException("source batch must belong to the writer generation");
        }
        boolean firstUseOfArtifact = writtenArtifacts.add(batch.sourceArtifact().id().value());
        boolean firstUseOfGenerationFile = writtenGenerationFiles.add(batch.sourcePath());
        generationWriter.writeBatch(lease, batch.batchId(), documentMapper.map(batch, firstUseOfArtifact, firstUseOfGenerationFile));
    }
}
