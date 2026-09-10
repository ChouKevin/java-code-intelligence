package com.java.semantic.query.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedMongoLifecycleIT {

    private static PublishedMongoLifecycle lifecycle;

    @BeforeAll
    static void startMongo() {
        lifecycle = PublishedMongoLifecycle.start();
    }

    @AfterAll
    static void stopMongo() {
        lifecycle.close();
    }

    @Test
    void gives_each_invocation_an_isolated_database_and_cleans_success_and_failure_paths() {
        String successfulDatabase;
        MongoTemplate successfulTemplate;
        try (PublishedMongoLifecycle.Invocation successful = lifecycle.openInvocation()) {
            successfulDatabase = successful.databaseName();
            successfulTemplate = successful.template();
            successfulTemplate.getCollection("corrupt").insertOne(new Document("state", "corrupt"));
        }

        String nextDatabase;
        try (PublishedMongoLifecycle.Invocation next = lifecycle.openInvocation()) {
            nextDatabase = next.databaseName();
            assertThat(next.template().getCollection("corrupt").countDocuments()).isZero();
        }

        PublishedMongoLifecycle.Invocation failed = lifecycle.openInvocation();
        String failedDatabase = failed.databaseName();
        MongoTemplate failedTemplate = failed.template();
        assertThatThrownBy(() -> {
            try (failed) {
                failedTemplate.getCollection("corrupt").insertOne(new Document("state", "corrupt"));
                throw new IllegalStateException("expected failure");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("expected failure");

        assertThat(List.of(successfulDatabase, nextDatabase, failedDatabase)).doesNotHaveDuplicates();
        assertDatabaseIsEmpty(successfulDatabase);
        assertDatabaseIsEmpty(failedDatabase);
        assertTemplateCannotUseClosedClient(successfulTemplate);
        assertTemplateCannotUseClosedClient(failedTemplate);
    }

    private static void assertDatabaseIsEmpty(String databaseName) {
        try (MongoClient client = MongoClients.create(lifecycle.connectionString())) {
            ArrayList<String> collections = client.getDatabase(databaseName).listCollectionNames().into(new ArrayList<>());
            assertThat(collections).isEmpty();
        }
    }

    private static void assertTemplateCannotUseClosedClient(MongoTemplate template) {
        assertThatThrownBy(() -> template.getCollection("corrupt").countDocuments())
                .isInstanceOf(IllegalStateException.class);
    }
}
