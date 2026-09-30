package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.support.JdtLsTestProperties;
import org.eclipse.jgit.api.Git;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void published_rebuild_for_the_same_revision_keeps_g1_documents_and_switches_only_to_g2() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "same_revision_rebuild");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore store = new MongoIndexJobStore(template);
            RepositoryIndexExporter exporter = (context, analysis) -> new RepositoryIndexExport(
                    List.of(FullIndexPublicationIT.validBatch(context.repositoryId(), analysis.snapshot().revision(),
                            context.generationId())), analysis.readinessEvidence());
            Path checkout = Files.createDirectories(temporaryDirectory.resolve("checkout"));
            FullIndexPublicationIT.writeCheckoutSource(checkout);
            JdtLsTestProperties.prepareSafeCheckoutRoot(checkout);
            String revision;
            try (Git git = Git.init().setDirectory(checkout.toFile()).call()) {
                git.add().addFilepattern(".").call();
                revision = git.commit().setMessage("source").setAuthor("Test", "test@example.test")
                        .setCommitter("Test", "test@example.test").call().name();
            }
            IndexBuildService service = FullIndexPublicationIT.service(template, store, exporter,
                    ignored -> new IndexBuildService.CheckedOutRepository(checkout,
                            new com.java.semantic.model.repository.RepositoryRevision(revision)));

            IndexJob first = start(store.admit(com.java.semantic.model.repository.RepositoryId.of("orders"),
                    new com.java.semantic.model.repository.RepositoryRevision(revision), false), store);
            service.build(first);
            assertThat(store.complete(first.id())).isTrue();
            String g1 = first.target().orElseThrow().generationId().value();
            Document original = template.getCollection(IndexCollections.SYMBOLS)
                    .find(new Document("repoId", "orders").append("generationId", g1)).first();

            IndexJob rebuilt = start(store.admit(com.java.semantic.model.repository.RepositoryId.of("orders"),
                    new com.java.semantic.model.repository.RepositoryRevision(revision), true), store);
            service.build(rebuilt);

            assertThat(template.getCollection(IndexCollections.SYMBOLS).find(new Document("repoId", "orders").append("generationId", g1))
                    .first().getString("symbolId")).isEqualTo(original.getString("symbolId"));
            Document current = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
            Document pointer = current.get("currentPointer", Document.class);
            assertThat(pointer.getString("revision")).isEqualTo(revision);
            assertThat(pointer.getString("generationId")).isEqualTo(rebuilt.target().orElseThrow().generationId().value());
            assertThat(pointer.getString("generationId")).isNotEqualTo(g1);
        }
    }

    private static IndexJob start(IndexJob accepted, MongoIndexJobStore store) {
        return store.startNextAccepted().orElseThrow();
    }
}
