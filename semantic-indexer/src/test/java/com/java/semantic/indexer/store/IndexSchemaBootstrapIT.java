package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class IndexSchemaBootstrapIT {
    @Test
    void creates_and_verifies_the_same_schema_idempotently() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            IndexSchemaBootstrap bootstrap = new IndexSchemaBootstrap(MongoSchemaTestSupport.template(container));
            String first = bootstrap.bootstrap();
            String second = bootstrap.bootstrap();
            assertThat(first).isEqualTo(IndexSchemaContract.fingerprint()).isEqualTo(second);
        }
    }

    @Test
    void refuses_a_conflicting_named_index() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            template.createCollection("repositories");
            template.getCollection("repositories").createIndex(new Document("unexpected", 1),
                    new com.mongodb.client.model.IndexOptions().name("repository_id_unique"));
            IndexSchemaBootstrap bootstrap = new IndexSchemaBootstrap(template);
            assertThatThrownBy(bootstrap::bootstrap).isInstanceOf(IndexSchemaConflictException.class);
        }
    }

    @Test
    void tolerates_spring_translated_namespace_exists_during_concurrent_collection_creation() throws InterruptedException {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            CountDownLatch writersPassedCollectionCheck = new CountDownLatch(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            IndexSchemaContract.CollectionSpec repositories = IndexSchemaContract.collections().getFirst();
            MongoIndexSchemaWriter.CollectionCreationGate simultaneousCreation = () -> {
                writersPassedCollectionCheck.countDown();
                await(writersPassedCollectionCheck);
            };
            MongoIndexSchemaWriter firstWriter = new MongoIndexSchemaWriter(template, simultaneousCreation);
            MongoIndexSchemaWriter secondWriter = new MongoIndexSchemaWriter(template, simultaneousCreation);
            Thread first = new Thread(() -> createOrRecordFailure(firstWriter, repositories, failures), "first-schema-writer");
            Thread second = new Thread(() -> createOrRecordFailure(secondWriter, repositories, failures), "second-schema-writer");

            first.start();
            second.start();
            first.join(TimeUnit.SECONDS.toMillis(10));
            second.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(failures).isEmpty();
            assertThat(new IndexSchemaBootstrap(template).bootstrap()).isEqualTo(IndexSchemaContract.fingerprint());
        }
    }

    private static void createOrRecordFailure(MongoIndexSchemaWriter writer, IndexSchemaContract.CollectionSpec collection,
                                              List<Throwable> failures) {
        try {
            writer.createOrVerify(collection);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            boolean completed = latch.await(10, TimeUnit.SECONDS);
            if (!completed) { throw new AssertionError("schema writer synchronization timed out"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("schema writer synchronization interrupted", exception);
        }
    }
}
