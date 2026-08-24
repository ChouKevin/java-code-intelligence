package com.java.semantic.indexer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.MongoGenerationWriter.StoredDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.repository.RepositoryId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MongoIndexBatchWriterTest {

    @Test
    void writes_an_out_of_order_source_chunk_without_duplicate_source_records() {
        RepositoryId repositoryId = new RepositoryId("orders");
        GenerationId generationId = new GenerationId("orders-generation");
        MongoGenerationWriter generationWriter = mock(MongoGenerationWriter.class);
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(generationWriter,
                new MongoGenerationWriter.GenerationLease(repositoryId, generationId, "job", "worker", 1L), mapper());
        List<String> batchIds = new CopyOnWriteArrayList<>();
        List<List<StoredDocument>> documents = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            batchIds.add(invocation.getArgument(1, String.class));
            documents.add(invocation.getArgument(2));
            return null;
        }).when(generationWriter).writeBatch(eq(new MongoGenerationWriter.GenerationLease(
                repositoryId, generationId, "job", "worker", 1L)), anyString(),
                org.mockito.ArgumentMatchers.<List<StoredDocument>>any());

        writer.write(batch(repositoryId, generationId, 1));
        writer.write(batch(repositoryId, generationId, 0));

        assertEquals(List.of("src/main/java/example/OrderService.java#1", "src/main/java/example/OrderService.java#0"), batchIds);
        assertEquals(1, documents.stream().flatMap(List::stream)
                .filter(document -> IndexCollections.SOURCE_ARTIFACTS.equals(document.collection())).count());
        assertEquals(1, documents.stream().flatMap(List::stream)
                .filter(document -> IndexCollections.GENERATION_FILES.equals(document.collection())).count());
    }

    @Test
    void rejects_a_batch_from_another_repository_or_generation_before_writing() {
        MongoGenerationWriter generationWriter = mock(MongoGenerationWriter.class);
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(generationWriter,
                new MongoGenerationWriter.GenerationLease(new RepositoryId("orders"), new GenerationId("orders-generation"),
                        "job", "worker", 1L), mapper());

        assertThrows(IllegalArgumentException.class,
                () -> writer.write(batch(new RepositoryId("payments"), new GenerationId("orders-generation"), 0)));

        verifyNoInteractions(generationWriter);
    }

    private static SourceIndexBatch batch(RepositoryId repositoryId, GenerationId generationId, int chunk) {
        return new SourceIndexBatch(repositoryId, generationId, "src/main/java/example/OrderService.java", chunk,
                SourceArtifactDocument.create("class OrderService {}"), List.of(), List.of(), List.of(), List.of());
    }

    private static SourceIndexBatchDocumentMapper mapper() {
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, mappingContext);
        converter.afterPropertiesSet();
        return new SourceIndexBatchDocumentMapper(converter);
    }
}
