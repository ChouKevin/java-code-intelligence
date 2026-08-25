package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.client.MongoClients;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Same-revision rebuilds switch only the published pointer and never mutate immutable generation rows. */
@Tag("mongo-it")
class SameRevisionRebuildIT {

    @Test
    void projection_v2_registers_the_named_search_lookup_index_required_before_generation_work() {
        IndexSchemaContract.CollectionSpec search = IndexSchemaContract.collections().stream()
                .filter(collection -> collection.name().equals(IndexCollections.SEARCH)).findFirst().orElseThrow();

        assertThat(search.indexes()).extracting(IndexSchemaContract.IndexSpec::name)
                .contains("search_generation_fact_lookup");
    }

    @Test
    void published_rebuild_for_the_same_revision_keeps_g1_documents_and_switches_only_to_g2() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "same_revision_rebuild");
            new IndexSchemaBootstrap(template).bootstrap();
            String revision = "a".repeat(40);
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(manifest("g1", revision, "1".repeat(64)));
            template.getCollection(IndexCollections.SYMBOLS).insertOne(new Document("repoId", "orders").append("generationId", "g1")
                    .append("symbolId", "original"));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(pointer(revision, "g1", "1".repeat(64)));

            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(manifest("g2", revision, "2".repeat(64)));
            template.getCollection(IndexCollections.SYMBOLS).insertOne(new Document("repoId", "orders").append("generationId", "g2")
                    .append("symbolId", "rebuilt"));
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set",
                    new Document("generationId", "g2").append("manifestDigest", "2".repeat(64)).append("committedJobId", "job-g2")));

            assertThat(template.getCollection(IndexCollections.SYMBOLS).find(new Document("generationId", "g1")).first().getString("symbolId"))
                    .isEqualTo("original");
            Document current = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
            assertThat(current.getString("revision")).isEqualTo(revision);
            assertThat(current.getString("generationId")).isEqualTo("g2");
        }
    }

    private static Document manifest(String generationId, String revision, String digest) {
        return new Document("repoId", "orders").append("generationId", generationId).append("sourceRevision", revision)
                .append("identityDigest", digest).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("writeState", "SEALED_VALID").append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList());
    }

    private static Document pointer(String revision, String generationId, String digest) {
        return new Document("repoId", "orders").append("revision", revision).append("generationId", generationId)
                .append("manifestDigest", digest).append("committedJobId", "job-g1").append("publishedAt", new Date());
    }
}
