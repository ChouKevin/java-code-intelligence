package com.java.semantic.query.store;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class MongoCurrentGenerationReadIT {

    @Test
    void pins_projection_reads_to_the_latest_current_revision_and_generation() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_query_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS, IndexCollections.SYMBOLS)) {
                template.createCollection(collection);
            }
            RepositoryRevision r1 = new RepositoryRevision("a".repeat(40));
            RepositoryRevision r2 = new RepositoryRevision("b".repeat(40));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(pointer("orders", r1, "g1", "1"));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(
                    manifest("orders", r1, "g1", "SEALED_VALID", "1"),
                    manifest("orders", r2, "g2", "WRITING", "2"),
                    manifest("orders", r2, "g3", "SEALED_VALID", "3")));
            template.getCollection(IndexCollections.SYMBOLS).insertMany(List.of(
                    new Document("repoId", "orders").append("generationId", "g1").append("symbolId", "old"),
                    new Document("repoId", "orders").append("generationId", "g2").append("symbolId", "partial"),
                    new Document("repoId", "orders").append("generationId", "g3").append("symbolId", "rebuild")));

            MongoCurrentGenerationReader reader = new MongoCurrentGenerationReader(template);
            assertThat(reader.read(new RepositoryId("orders"), r1).generationId().value()).isEqualTo("g1");

            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", "g2"),
                    new Document("$set", new Document("writeState", "SEALED_VALID")));
            template.getCollection(IndexCollections.REPOSITORIES).replaceOne(new Document("repoId", "orders"), pointer("orders", r2, "g2", "2"));

            assertThatThrownBy(() -> reader.read(new RepositoryId("orders"), r1))
                    .isInstanceOf(RevisionOutdatedException.class)
                    .extracting(exception -> ((RevisionOutdatedException) exception).currentGeneration().revision())
                    .isEqualTo(r2);
            assertThat(reader.read(new RepositoryId("orders"), r2).generationId().value()).isEqualTo("g2");
            assertThat(new MongoSymbolReader(template).read(new RepositoryId("orders"), r2))
                    .extracting(document -> document.getString("symbolId")).containsExactly("partial");

            template.getCollection(IndexCollections.REPOSITORIES).replaceOne(new Document("repoId", "orders"), pointer("orders", r2, "g3", "3"));
            assertThat(reader.read(new RepositoryId("orders"), r2).generationId().value()).isEqualTo("g3");
            assertThat(new MongoSymbolReader(template).read(new RepositoryId("orders"), r2))
                    .extracting(document -> document.getString("symbolId")).containsExactly("rebuild");
        }
    }

    @Test
    void source_reader_requires_selected_generation_path_membership_for_shared_artifacts() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_source_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS,
                    IndexCollections.GENERATION_FILES, IndexCollections.SOURCE_ARTIFACTS)) {
                template.createCollection(collection);
            }
            RepositoryRevision revision = new RepositoryRevision("a".repeat(40));
            template.getCollection(IndexCollections.REPOSITORIES).insertMany(List.of(pointer("repoa", revision, "g1", "1"),
                    pointer("repob", revision, "g1", "2")));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(manifest("repoa", revision, "g1", "SEALED_VALID", "1"),
                    manifest("repob", revision, "g1", "SEALED_VALID", "2")));
            template.getCollection(IndexCollections.SOURCE_ARTIFACTS).insertOne(new Document("sourceArtifactId", "shared").append("contentHash", "shared").append("utf8Content", "private"));
            template.getCollection(IndexCollections.GENERATION_FILES).insertOne(new Document("repoId", "repoa").append("generationId", "g1").append("sourcePath", "A.java").append("sourceArtifactId", "shared"));
            MongoSourceReader reader = new MongoSourceReader(template);
            assertThat(reader.read(new RepositoryId("repoa"), revision, "A.java").getString("utf8Content")).isEqualTo("private");
            assertThatThrownBy(() -> reader.read(new RepositoryId("repob"), revision, "A.java")).isInstanceOf(IndexNotReadyException.class);
        }
    }

    @Test
    void rejects_a_current_pointer_when_its_manifest_has_a_different_source_revision() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_revision_mismatch_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS, IndexCollections.SYMBOLS)) {
                template.createCollection(collection);
            }
            RepositoryRevision pointerRevision = new RepositoryRevision("a".repeat(40));
            RepositoryRevision manifestRevision = new RepositoryRevision("b".repeat(40));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(pointer("orders", pointerRevision, "g1", "1"));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(
                    manifest("orders", manifestRevision, "g1", "SEALED_VALID", "1"));
            template.getCollection(IndexCollections.SYMBOLS).insertOne(
                    new Document("repoId", "orders").append("generationId", "g1").append("symbolId", "unreachable"));

            assertThatThrownBy(() -> new MongoCurrentGenerationReader(template).read(new RepositoryId("orders"), pointerRevision))
                    .isInstanceOf(IndexNotReadyException.class);
            assertThatThrownBy(() -> new MongoSymbolReader(template).read(new RepositoryId("orders"), pointerRevision))
                    .isInstanceOf(IndexNotReadyException.class);
        }
    }

    @Test
    void lists_only_valid_published_repositories_and_omits_unpublished_coordinators() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoClient client = MongoClients.create(container.getConnectionString());
            MongoTemplate template = new MongoTemplate(client, "semantic_repository_list_test");
            for (String collection : List.of(IndexCollections.REPOSITORIES, IndexCollections.GENERATION_MANIFESTS)) {
                template.createCollection(collection);
            }
            RepositoryRevision revision = new RepositoryRevision("a".repeat(40));
            template.getCollection(IndexCollections.REPOSITORIES).insertMany(List.of(
                    pointer("published", revision, "g1", "1"),
                    new Document("repoId", "staged").append("activeJobId", "job-1"),
                    pointer("incompatible", revision, "g1", "2"),
                    withoutCommittedJobId(pointer("missing-job", revision, "g1", "3"))));
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertMany(List.of(
                    manifest("published", revision, "g1", "SEALED_VALID", "1"),
                    new Document(manifest("incompatible", revision, "g1", "SEALED_VALID", "2")).append("schemaVersion", 99),
                    manifest("missing-job", revision, "g1", "SEALED_VALID", "3")));
            MongoCurrentGenerationReader reader = new MongoCurrentGenerationReader(template);
            assertThat(reader.list()).extracting(current -> current.repositoryId().value()).containsExactly("published");
            assertThatThrownBy(() -> reader.read(new RepositoryId("staged"), revision)).isInstanceOf(IndexNotReadyException.class);
            assertThatThrownBy(() -> reader.read(new RepositoryId("incompatible"), revision)).isInstanceOf(IndexNotReadyException.class);
            assertThatThrownBy(() -> reader.read(new RepositoryId("missing-job"), revision)).isInstanceOf(IndexNotReadyException.class);
        }
    }

    private static Document pointer(String repositoryId, RepositoryRevision revision, String generationId, String digestDigit) {
        return new Document("repoId", repositoryId)
                .append("revision", revision.value())
                .append("generationId", generationId)
                .append("manifestDigest", digest(digestDigit))
                .append("committedJobId", "job-" + generationId)
                .append("publishedAt", new Date());
    }

    private static Document manifest(String repositoryId, RepositoryRevision revision, String generationId, String state, String digestDigit) {
        List<Document> projections = List.of(
                new Document("name", "SOURCES").append("version", 1),
                new Document("name", "SYMBOLS").append("version", 1),
                new Document("name", "RELATIONS").append("version", 1),
                new Document("name", "ENTRY_POINTS").append("version", 1),
                new Document("name", "SEARCH").append("version", 1));
        return new Document("repoId", repositoryId).append("sourceRevision", revision.value()).append("generationId", generationId)
                .append("writeState", state).append("identityDigest", digest(digestDigit)).append("schemaVersion", 1)
                .append("projectionVersions", projections);
    }

    private static String digest(String digit) {
        return digit.repeat(64);
    }

    private static Document withoutCommittedJobId(Document pointer) {
        Document malformed = new Document(pointer);
        malformed.remove("committedJobId");
        return malformed;
    }
}
