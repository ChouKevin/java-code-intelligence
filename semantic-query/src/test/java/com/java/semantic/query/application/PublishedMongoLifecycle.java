package com.java.semantic.query.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

final class PublishedMongoLifecycle implements AutoCloseable {

    private final MongoDBContainer container;

    private PublishedMongoLifecycle(MongoDBContainer container) {
        this.container = container;
    }

    static PublishedMongoLifecycle start() {
        MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"));
        container.start();
        return new PublishedMongoLifecycle(container);
    }

    Invocation openInvocation() {
        String databaseName = "published_" + UUID.randomUUID().toString().replace("-", "");
        MongoClient client = MongoClients.create(container.getConnectionString());
        try {
            return new Invocation(client, databaseName);
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    String connectionString() {
        return container.getConnectionString();
    }

    @Override
    public void close() {
        container.stop();
    }

    static final class Invocation implements AutoCloseable {

        private final MongoClient client;
        private final String databaseName;
        private final MongoTemplate template;

        private Invocation(MongoClient client, String databaseName) {
            this.client = client;
            this.databaseName = databaseName;
            this.template = new MongoTemplate(client, databaseName);
        }

        String databaseName() {
            return databaseName;
        }

        MongoTemplate template() {
            return template;
        }

        @Override
        public void close() {
            try {
                client.getDatabase(databaseName).drop();
            } finally {
                client.close();
            }
        }
    }
}
