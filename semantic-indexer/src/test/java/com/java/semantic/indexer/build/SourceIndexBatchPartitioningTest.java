package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourceIndexBatchPartitioningTest {

    private static final RepositoryId REPOSITORY_ID = new RepositoryId("batch-repository");
    private static final RepositoryRevision REVISION = new RepositoryRevision("a".repeat(40));
    private static final GenerationId GENERATION_ID = new GenerationId("batch-generation");
    private static final String SOURCE_PATH = "src/main/java/sample/Bulk.java";
    private static final SourceArtifactDocument ARTIFACT = SourceArtifactDocument.create("package sample; class Bulk { }");

    @Test
    void partitions_exactly_at_the_query_document_boundary_without_loss_or_duplicate_batch_ids() {
        List<SymbolDocument> symbols = IntStream.range(0, SourceIndexBatch.MAX_QUERY_DOCUMENTS + 1)
                .mapToObj(this::symbol).toList();

        List<SourceIndexBatch> first = JdtLsRepositoryIndexExporter.split(REPOSITORY_ID, GENERATION_ID, SOURCE_PATH,
                ARTIFACT, Optional.empty(), SourceIndexScope.from(symbols), symbols, List.of(), List.of(), List.of());
        List<SourceIndexBatch> second = JdtLsRepositoryIndexExporter.split(REPOSITORY_ID, GENERATION_ID, SOURCE_PATH,
                ARTIFACT, Optional.empty(), SourceIndexScope.from(symbols), symbols, List.of(), List.of(), List.of());

        assertThat(first).extracting(SourceIndexBatch::queryDocumentCount)
                .containsExactly(SourceIndexBatch.MAX_QUERY_DOCUMENTS, 1);
        assertThat(first).extracting(SourceIndexBatch::batchId).containsExactly(SOURCE_PATH + "#0", SOURCE_PATH + "#1");
        assertThat(first).allSatisfy(batch -> {
            assertThat(batch.repositoryId()).isEqualTo(REPOSITORY_ID);
            assertThat(batch.generationId()).isEqualTo(GENERATION_ID);
            assertThat(batch.sourcePath()).isEqualTo(SOURCE_PATH);
            assertThat(batch.sourceArtifact()).isEqualTo(ARTIFACT);
            assertThat(batch.sourceScope()).isEqualTo(SourceIndexScope.from(symbols));
        });
        assertThat(first.stream().flatMap(batch -> batch.symbols().stream()).map(symbol -> symbol.fact().id()).toList())
                .containsExactlyElementsOf(symbols.stream().map(symbol -> symbol.fact().id()).toList());
        assertThat(second).isEqualTo(first);
    }

    private SymbolDocument symbol(int index) {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("sample", "Type" + index), SOURCE_PATH);
        CodeFactIdentity identity = new CodeFactIdentity(REPOSITORY_ID, REVISION, CodeFactKind.TYPE, type);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SourceRange range = new SourceRange(SOURCE_PATH, new SyntaxRange(new SyntaxPosition(0, index), new SyntaxPosition(0, index + 1)));
        return new SymbolDocument(REPOSITORY_ID, GENERATION_ID, fact, CodeFactKind.TYPE, "sample", "Type" + index,
                "sample.Type" + index, new DeclaredType("sample.Type" + index), Set.of(), List.of(), ARTIFACT.id(), range);
    }
}
