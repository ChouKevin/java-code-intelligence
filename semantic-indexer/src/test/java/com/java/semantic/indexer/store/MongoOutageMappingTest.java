package com.java.semantic.indexer.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoOutageMappingTest {

    @Test
    void maps_direct_manifest_write_outage_without_waiting_for_default_driver_timeouts() {
        try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:1/semantic_outage?serverSelectionTimeoutMS=100&connectTimeoutMS=100&socketTimeoutMS=100")) {
            MongoTemplate template = new MongoTemplate(client, "semantic_outage");

            assertThatThrownBy(() -> new MongoGenerationWriter(template).insertManifest(
                    new GenerationWriteContext(RepositoryId.of("orders"), new GenerationId("g1"), "job-1"), new Document("repoId", "orders")))
                    .isInstanceOf(SemanticIndexUnavailableException.class)
                    .hasMessage("SEMANTIC_INDEX_UNAVAILABLE");
        }
    }
}
