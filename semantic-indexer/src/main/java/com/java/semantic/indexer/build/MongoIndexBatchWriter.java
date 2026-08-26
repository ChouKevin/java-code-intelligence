package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoGenerationWriter;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Production bridge from projection batches to one job-owned generation. */
public final class MongoIndexBatchWriter implements IndexBatchWriter {
    private final MongoGenerationWriter generationWriter;
    private final GenerationWriteContext context;
    private final SourceIndexBatchDocumentMapper mapper;
    private final Set<String> writtenArtifacts = ConcurrentHashMap.newKeySet();
    private final Set<String> writtenGenerationFiles = ConcurrentHashMap.newKeySet();

    public MongoIndexBatchWriter(MongoGenerationWriter generationWriter, GenerationWriteContext context, SourceIndexBatchDocumentMapper mapper) {
        this.generationWriter = Objects.requireNonNull(generationWriter, "generation writer is required");
        this.context = Objects.requireNonNull(context, "generation write context is required");
        this.mapper = Objects.requireNonNull(mapper, "document mapper is required");
    }

    @Override
    public void write(SourceIndexBatch batch) {
        Objects.requireNonNull(batch, "source batch is required");
        if (!context.repositoryId().equals(batch.repositoryId()) || !context.generationId().equals(batch.generationId())) {
            throw new IllegalArgumentException("source batch must belong to the writer generation");
        }
        generationWriter.writeBatch(context, batch.batchId(), mapper.map(batch,
                writtenArtifacts.add(batch.sourceArtifact().id().value()), writtenGenerationFiles.add(batch.sourcePath())));
    }
}
