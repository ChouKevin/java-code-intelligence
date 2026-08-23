package com.java.semantic.indexer.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

final class MongoSchemaTestSupport {
    private MongoSchemaTestSupport() { }

    static MongoDBContainer container() {
        MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"));
        container.start();
        return container;
    }

    static MongoTemplate template(MongoDBContainer container) {
        MongoClient client = MongoClients.create(container.getConnectionString());
        return new MongoTemplate(client, "semantic_store_test");
    }
}
