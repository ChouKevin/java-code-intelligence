package com.java.semantic.indexer.build;

import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import java.util.List;
import java.util.Objects;

/** Bounded write unit: every projected record originates from exactly one source path. */
public record SourceIndexBatch(
        RepositoryId repositoryId,
        GenerationId generationId,
        String sourcePath,
        int sourceChunk,
        SourceArtifactDocument sourceArtifact,
        List<SymbolDocument> symbols,
        List<RelationDocument> relations,
        List<EntryPointDocument> entryPoints,
        List<SearchDocument> search) {

    /** Maximum query documents in one write unit; source artifact and generation-file records are not query documents. */
    public static final int MAX_QUERY_DOCUMENTS = 1_000;

    public SourceIndexBatch {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        sourcePath = Objects.requireNonNull(sourcePath, "source path is required");
        if (sourceChunk < 0) {
            throw new IllegalArgumentException("source chunk must not be negative");
        }
        sourceArtifact = Objects.requireNonNull(sourceArtifact, "source artifact is required");
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols are required"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations are required"));
        entryPoints = List.copyOf(Objects.requireNonNull(entryPoints, "entry points are required"));
        search = List.copyOf(Objects.requireNonNull(search, "search is required"));
        if (queryDocumentCount(symbols, relations, entryPoints, search) > MAX_QUERY_DOCUMENTS) {
            throw new IllegalArgumentException("source batch exceeds maximum query document count");
        }
    }

    public String batchId() {
        return sourcePath + "#" + sourceChunk;
    }

    public int queryDocumentCount() {
        return queryDocumentCount(symbols, relations, entryPoints, search);
    }

    private static int queryDocumentCount(List<SymbolDocument> symbols, List<RelationDocument> relations,
                                          List<EntryPointDocument> entryPoints, List<SearchDocument> search) {
        return symbols.size() + relations.size() + entryPoints.size() + search.size();
    }

}
