package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Same-revision rebuilds switch only the published pointer and never mutate immutable generation rows. */
@Tag("mongo-it")
class SameRevisionRebuildIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void projection_v2_registers_the_named_search_lookup_index_required_before_generation_work() {
        IndexSchemaContract.CollectionSpec search = IndexSchemaContract.collections().stream()
                .filter(collection -> collection.name().equals(IndexCollections.SEARCH)).findFirst().orElseThrow();

        assertThat(search.indexes()).extracting(IndexSchemaContract.IndexSpec::name)
                .contains("search_generation_fact_lookup");
    }

    @Test
    void published_rebuild_for_the_same_revision_keeps_g1_documents_and_switches_only_to_g2() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "same_revision_rebuild");
            new IndexSchemaBootstrap(template).bootstrap();
            String revision = "a".repeat(40);
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryIndexExporter exporter = (repositoryId, requestedRevision, generationId, plan) ->
                    List.of(FullIndexPublicationIT.validBatch(repositoryId, requestedRevision, generationId));
            Path checkout = Files.createDirectories(temporaryDirectory.resolve("checkout"));
            IndexBuildService service = FullIndexPublicationIT.service(template, store, exporter,
                    ignored -> new IndexBuildService.CheckedOutRepository(checkout,
                            new com.java.semantic.model.repository.RepositoryRevision(revision)));

            IndexJob first = claim(store.admit(com.java.semantic.model.repository.RepositoryId.of("orders"),
                    new com.java.semantic.model.repository.RepositoryRevision(revision), false), store);
            service.build(first);
            String g1 = first.generationId().value();
            Document original = template.getCollection(IndexCollections.SYMBOLS)
                    .find(new Document("repoId", "orders").append("generationId", g1)).first();

            IndexJob rebuilt = claim(store.admit(com.java.semantic.model.repository.RepositoryId.of("orders"),
                    new com.java.semantic.model.repository.RepositoryRevision(revision), true), store);
            service.build(rebuilt);

            assertThat(template.getCollection(IndexCollections.SYMBOLS).find(new Document("repoId", "orders").append("generationId", g1))
                    .first().getString("symbolId")).isEqualTo(original.getString("symbolId"));
            Document current = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
            assertThat(current.getString("revision")).isEqualTo(revision);
            assertThat(current.getString("generationId")).isEqualTo(rebuilt.generationId().value());
            assertThat(current.getString("generationId")).isNotEqualTo(g1);
        }
    }

    private static IndexJob claim(IndexJob accepted, MongoIndexJobStore store) {
        return store.claim(accepted.id(), "worker-1", Duration.ofMinutes(5)).orElseThrow();
    }
}
