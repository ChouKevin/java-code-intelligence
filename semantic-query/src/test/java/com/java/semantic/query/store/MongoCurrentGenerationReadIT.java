package com.java.semantic.query.store;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoCurrentGenerationReadIT {
    @Test
    void exposes_only_the_current_sealed_generation() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_query_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS, IndexCollections.SYMBOLS)) { template.createCollection(collection); }
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("current", new Document("generationId", "g1").append("manifestDigest", "a")));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(
                    manifest("orders", "g1", "SEALED_VALID", "a"),
                    manifest("orders", "g2", "WRITING", "b")));
            template.getCollection(IndexCollections.SYMBOLS).insertMany(List.of(
                    new Document("repoId", "orders").append("generationId", "g1").append("symbolId", "old"),
                    new Document("repoId", "orders").append("generationId", "g2").append("symbolId", "current")));
            MongoCurrentGenerationReader reader = new MongoCurrentGenerationReader(template);
            assertThat(reader.read(new RepositoryId("orders")).generationId()).isEqualTo(new GenerationId("g1"));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"), new Document("$set", new Document("writeState", "SEALED_VALID")));
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", "orders"), new Document("$set", new Document("current", new Document("generationId", "g2").append("manifestDigest", "b"))));
            assertThat(reader.read(new RepositoryId("orders")).generationId()).isEqualTo(new GenerationId("g2"));
            assertThat(new MongoSymbolReader(template).read(new RepositoryId("orders"))).extracting(document -> document.getString("symbolId")).containsExactly("current");
        }
    }

    @Test
    void source_reader_requires_selected_generation_path_membership_for_shared_artifacts() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_source_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS,
                    IndexCollections.GENERATION_FILES, IndexCollections.SOURCE_ARTIFACTS)) { template.createCollection(collection); }
            template.getCollection(IndexCollections.REPOSITORIES).insertMany(List.of(new Document("repoId", "repoa").append("current", new Document("generationId", "g1").append("manifestDigest", "a")), new Document("repoId", "repob").append("current", new Document("generationId", "g1").append("manifestDigest", "b"))));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(manifest("repoa", "g1", "SEALED_VALID", "a"), manifest("repob", "g1", "SEALED_VALID", "b")));
            template.getCollection(IndexCollections.SOURCE_ARTIFACTS).insertOne(new Document("sourceArtifactId", "shared").append("contentHash", "shared").append("utf8Content", "private"));
            template.getCollection(IndexCollections.GENERATION_FILES).insertOne(new Document("repoId", "repoa").append("generationId", "g1").append("sourcePath", "A.java").append("sourceArtifactId", "shared"));
            MongoSourceReader reader = new MongoSourceReader(template);
            assertThat(reader.read(new RepositoryId("repoa"), "A.java").getString("utf8Content")).isEqualTo("private");
            assertThatThrownBy(() -> reader.read(new RepositoryId("repob"), "A.java")).isInstanceOf(IndexNotReadyException.class);
        }
    }

    @Test
    void rejects_a_repository_without_a_current_pointer() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_not_ready_test");
            template.createCollection(IndexCollections.REPOSITORIES);
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("activeJobId", "staged"));
            assertThatThrownBy(() -> new MongoCurrentGenerationReader(template).read(new RepositoryId("orders"))).isInstanceOf(IndexNotReadyException.class).hasMessage("INDEX_NOT_READY");
        }
    }

    @Test
    void lists_only_valid_published_repositories_and_rejects_incompatible_manifest() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_repository_list_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS)) { template.createCollection(collection); }
            template.getCollection(IndexCollections.REPOSITORIES).insertMany(List.of(
                    new Document("repoId", "published").append("current", new Document("generationId", "g1").append("manifestDigest", "good")),
                    new Document("repoId", "staged").append("activeJobId", "job-1"),
                    new Document("repoId", "malformed").append("current", "not-a-pointer-document"),
                    new Document("repoId", "incompatible").append("current", new Document("generationId", "g1").append("manifestDigest", "bad"))));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(
                    manifest("published", "g1", "SEALED_VALID", "good"),
                    new Document(manifest("incompatible", "g1", "SEALED_VALID", "bad")).append("schemaVersion", 99)));
            MongoCurrentGenerationReader reader = new MongoCurrentGenerationReader(template);
            assertThat(reader.list()).extracting(current -> current.repositoryId().value()).containsExactly("published");
            assertThatThrownBy(() -> reader.read(new RepositoryId("staged"))).isInstanceOf(IndexNotReadyException.class);
            assertThatThrownBy(() -> reader.read(new RepositoryId("incompatible"))).isInstanceOf(IndexNotReadyException.class);
        }
    }

    private static Document manifest(String repositoryId, String generationId, String state, String digest) {
        List<Document> projections = List.of(
                new Document("name", "SOURCES").append("version", 1),
                new Document("name", "SYMBOLS").append("version", 1),
                new Document("name", "RELATIONS").append("version", 1),
                new Document("name", "ENTRY_POINTS").append("version", 1),
                new Document("name", "SEARCH").append("version", 1));
        return new Document("repoId", repositoryId).append("generationId", generationId).append("writeState", state)
                .append("identityDigest", digest).append("schemaVersion", 1).append("projectionVersions", projections);
    }
}
