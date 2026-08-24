package com.java.semantic.query.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.query.application.IndexNotReadyException;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoIndexSchemaVerifierIT {
    private static MongoDBContainer container;
    private static MongoTemplate template;

    @BeforeAll
    static void startMongo() {
        container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"));
        container.start();
        template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic_query_schema_test");
    }

    @AfterAll
    static void stopMongo() { container.stop(); }

    @BeforeEach
    void clearDatabase() { template.getDb().drop(); }

    @Test
    void requires_the_complete_schema_but_allows_zero_repositories() {
        MongoIndexSchemaVerifier verifier = new MongoIndexSchemaVerifier(template, Duration.ofSeconds(2));
        assertThatThrownBy(verifier::verify).isInstanceOf(IndexNotReadyException.class);

        createExpectedSchema();

        assertThat(verifier.verify()).isEqualTo(IndexSchemaContract.fingerprint());
    }

    @Test
    void rejects_the_same_compound_keys_in_the_wrong_order() {
        createExpectedSchema();
        IndexSchemaContract.CollectionSpec symbols = IndexSchemaContract.collections().stream()
                .filter(collection -> collection.name().equals("symbols")).findFirst().orElseThrow();
        template.getCollection(symbols.name()).dropIndex("symbol_unique");
        template.getCollection(symbols.name()).createIndex(new Document("generationId", 1).append("repoId", 1).append("symbolId", 1),
                new IndexOptions().name("symbol_unique").unique(true));

        assertThatThrownBy(() -> new MongoIndexSchemaVerifier(template, Duration.ofSeconds(2)).verify())
                .isInstanceOf(IndexNotReadyException.class);
    }

    private void createExpectedSchema() {
        for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) {
            template.createCollection(collection.name());
            for (IndexSchemaContract.IndexSpec index : collection.indexes()) {
                IndexOptions options = new IndexOptions().name(index.name()).unique(index.unique());
                if (!index.partialFilter().isEmpty()) { options.partialFilterExpression(new Document(index.partialFilter())); }
                template.getCollection(collection.name()).createIndex(new Document(index.keys()), options);
            }
        }
    }
}
