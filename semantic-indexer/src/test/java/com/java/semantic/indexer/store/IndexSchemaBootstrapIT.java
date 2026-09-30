package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.PreparationRequest;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.indexer.job.GitEvidenceJob;
import com.java.semantic.indexer.job.GitEvidenceJobHandler;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void creates_the_nonunique_git_evidence_manifest_owner_lookup_idempotently() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            IndexSchemaBootstrap bootstrap = new IndexSchemaBootstrap(template);

            bootstrap.bootstrap();
            bootstrap.bootstrap();

            assertThat(template.getCollection("git_evidence_manifests").listIndexes().into(new ArrayList<>())).anySatisfy(index -> {
                assertThat(index.getString("name")).isEqualTo("git_evidence_manifest_owner_lookup");
                assertThat(index.get("key", Document.class)).containsExactlyEntriesOf(new Document("repoId", 1).append("kind", 1).append("ownerJobId", 1));
                assertThat(index.getBoolean("unique", false)).isFalse();
            });
        }
    }

    @Test
    void rejects_duplicate_review_ids_within_a_repository_but_allows_the_same_review_id_in_another_repository() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            MongoCollection<Document> manifests = template.getCollection(IndexCollections.REVIEW_MANIFESTS);

            manifests.insertOne(new Document("repoId", "orders").append("reviewId", "review-1"));

            assertThatThrownBy(() -> manifests.insertOne(new Document("repoId", "orders").append("reviewId", "review-1")))
                    .isInstanceOf(MongoWriteException.class);
            assertThatCode(() -> manifests.insertOne(new Document("repoId", "billing").append("reviewId", "review-1")))
                    .doesNotThrowAnyException();
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
    void wrong_request_identity_filter_blocks_bootstrap_and_generation() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            IndexSchemaBootstrap bootstrap = new IndexSchemaBootstrap(template);
            bootstrap.bootstrap();
            MongoCollection<Document> jobs = template.getCollection(IndexCollections.INDEX_JOBS);
            jobs.dropIndex("preparation_request_unique");
            jobs.createIndex(new Document("repoId", 1).append("requestId", 1),
                    new com.mongodb.client.model.IndexOptions().name("preparation_request_unique").unique(true)
                            .partialFilterExpression(new Document("requestId", new Document("$type", "int"))));

            assertThatThrownBy(bootstrap::bootstrap).isInstanceOf(IndexSchemaConflictException.class);
            assertThatThrownBy(() -> new MongoGenerationWriter(template).verifySchemaBeforeGeneration())
                    .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);
        }
    }

    @Test
    void worker_schema_gate_rejects_missing_or_conflicting_schema_before_projection_generation() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);

            assertThatThrownBy(writer::verifySchemaBeforeGeneration).isInstanceOf(IndexSchemaMaintenanceRequiredException.class);
            new IndexSchemaBootstrap(template).bootstrap();
            writer.verifySchemaBeforeGeneration();

            template.getCollection("search").dropIndex("search_generation_order");
            template.getCollection("search").createIndex(new Document("unexpected", 1),
                    new com.mongodb.client.model.IndexOptions().name("search_generation_order"));
            assertThatThrownBy(writer::verifySchemaBeforeGeneration).isInstanceOf(IndexSchemaMaintenanceRequiredException.class);
        }
    }

    @Test
    void git_evidence_writer_rejects_missing_schema_before_any_manifest_write() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            RepositoryId repositoryId = RepositoryId.of("orders");
            IndexJob job = runningMetadata(repositoryId);

            assertThatThrownBy(() -> new GitEvidencePublicationStore(template).beginCatalog(job, java.time.Instant.now()))
                    .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);
            assertThat(template.collectionExists("git_evidence_manifests")).isFalse();
        }
    }

    @Test
    void git_evidence_handler_rejects_missing_schema_before_clone_fetch_or_manifest_cleanup_writes() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            RepositoryId repositoryId = RepositoryId.of("orders");
            RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", java.nio.file.Path.of("target/orders"),
                    "file:///target/orders.git", "main");
            RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
            GitRepositoryPort git = mock(GitRepositoryPort.class);
            GitEvidencePublicationStore evidence = spy(new GitEvidencePublicationStore(template));
            IndexJob job = runningMetadata(repositoryId);
            when(repositories.get(repositoryId)).thenReturn(runtime);

            assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                    mock(RepositoryMutationListener.class)).prepare(job))
                    .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);

            verify(evidence, never()).fail(job);
            verify(git, never()).isCloned(runtime.workingTree());
            assertThat(template.collectionExists("git_evidence_manifests")).isFalse();
        }
    }

    @Test
    void git_evidence_handler_rejects_a_conflicting_schema_before_clone_fetch_or_manifest_cleanup_writes() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            template.getCollection("search").dropIndex("search_generation_order");
            template.getCollection("search").createIndex(new Document("unexpected", 1),
                    new com.mongodb.client.model.IndexOptions().name("search_generation_order"));
            RepositoryId repositoryId = RepositoryId.of("orders");
            RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", java.nio.file.Path.of("target/orders"),
                    "file:///target/orders.git", "main");
            RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
            GitRepositoryPort git = mock(GitRepositoryPort.class);
            GitEvidencePublicationStore evidence = spy(new GitEvidencePublicationStore(template));
            IndexJob job = runningMetadata(repositoryId);
            when(repositories.get(repositoryId)).thenReturn(runtime);

            assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                    mock(RepositoryMutationListener.class)).prepare(job))
                    .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);

            verify(evidence, never()).fail(job);
            verify(git, never()).isCloned(runtime.workingTree());
            assertThat(template.getCollection("git_evidence_manifests").countDocuments()).isZero();
        }
    }

    @Test
    void git_evidence_ready_rejects_missing_tampered_and_wrong_identity_rows_without_publication() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            RepositoryId orders = RepositoryId.of("orders");
            IndexJob owner = admitRunningMetadata(template, orders);

            com.java.semantic.model.git.GitCatalogManifest missing = store.beginCatalog(owner, Instant.now());
            assertThatThrownBy(() -> store.readyCatalog(missing, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, missing.catalogId().value());

            com.java.semantic.model.git.GitCatalogManifest malformed = store.beginCatalog(owner, Instant.now());
            template.getCollection("git_branches").insertOne(new Document("repoId", "orders").append("catalogId", malformed.catalogId().value())
                    .append("ordinal", 0L).append("branch", "main"));
            assertThatThrownBy(() -> store.readyCatalog(malformed, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, malformed.catalogId().value());

            com.java.semantic.model.git.GitCatalogManifest wrongIdentity = store.beginCatalog(owner, Instant.now());
            template.getCollection("git_branches").insertOne(new Document("repoId", "billing").append("catalogId", wrongIdentity.catalogId().value())
                    .append("ordinal", 0L).append("branch", "main").append("head", "1".repeat(40)));
            assertThatThrownBy(() -> store.readyCatalog(wrongIdentity, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, wrongIdentity.catalogId().value());
        }
    }

    @Test
    void git_history_ready_rejects_noncontiguous_rows_and_a_head_that_differs_from_the_manifest() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            RepositoryId orders = RepositoryId.of("orders");
            RepositoryRevision revision = RepositoryRevision.ofSha("1".repeat(40));
            com.java.semantic.model.git.GitEvidenceId catalogId = com.java.semantic.model.git.GitEvidenceId.create();
            IndexJob owner = admitRunningMetadata(template, orders);

            com.java.semantic.model.git.GitHistoryManifest noncontiguous = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());
            template.getCollection("git_commits").insertOne(commitRow("orders", noncontiguous.historyId().value(), 1L, revision.value()));
            assertThatThrownBy(() -> store.readyHistory(noncontiguous, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, noncontiguous.historyId().value());

            com.java.semantic.model.git.GitHistoryManifest wrongHead = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());
            template.getCollection("git_commits").insertOne(commitRow("orders", wrongHead.historyId().value(), 0L, "2".repeat(40)));
            assertThatThrownBy(() -> store.readyHistory(wrongHead, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, wrongHead.historyId().value());

            com.java.semantic.model.git.GitHistoryManifest missingSubject = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());
            Document missingSubjectRow = commitRow("orders", missingSubject.historyId().value(), 0L, revision.value());
            missingSubjectRow.remove("subject");
            template.getCollection("git_commits").insertOne(missingSubjectRow);
            assertThatThrownBy(() -> store.readyHistory(missingSubject, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, missingSubject.historyId().value());

            com.java.semantic.model.git.GitHistoryManifest wrongSubjectType = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());
            template.getCollection("git_commits").insertOne(commitRow("orders", wrongSubjectType.historyId().value(), 0L, revision.value())
                    .append("subject", 7));
            assertThatThrownBy(() -> store.readyHistory(wrongSubjectType, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, wrongSubjectType.historyId().value());

            com.java.semantic.model.git.GitHistoryManifest tampered = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());
            store.appendCommit(tampered, 0L, new com.java.semantic.model.git.GitCommit(revision, List.of(), "original", Instant.now()));
            template.getCollection("git_commits").updateOne(new Document("historyId", tampered.historyId().value()),
                    new Document("$set", new Document("subject", "substituted but valid")));
            assertThatThrownBy(() -> store.readyHistory(tampered, 1L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, tampered.historyId().value());
        }
    }

    @Test
    void git_history_ready_rejects_an_empty_stream_without_publishing_the_manifest() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            RepositoryId orders = RepositoryId.of("orders");
            RepositoryRevision revision = RepositoryRevision.ofSha("1".repeat(40));
            com.java.semantic.model.git.GitEvidenceId catalogId = com.java.semantic.model.git.GitEvidenceId.create();
            IndexJob owner = admitRunningMetadata(template, orders);
            com.java.semantic.model.git.GitHistoryManifest history = store.beginHistory(owner, catalogId,
                    "main", revision, Instant.now());

            assertThatThrownBy(() -> store.readyHistory(history, 0L)).isInstanceOf(PublicationConflictException.class);
            assertPreparing(template, history.historyId().value());
        }
    }

    @Test
    void permits_a_search_row_with_tokens_and_method_parameter_arrays() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            org.springframework.data.mongodb.core.MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();

            assertThatCode(() -> template.getCollection("search").insertOne(new Document("repoId", "orders")
                    .append("generationId", "g1").append("factId", "fact-1").append("kind", "METHOD")
                    .append("tokens", List.of("get", "2", "d")).append("package", "example.payment")
                    .append("authority", "SYMBOLS").append("canonical", "method[1]example.payment.Invoice#get2D(java.lang.String)")
                    .append("scopePackage", "example.payment").append("scopeClass", "Invoice")
                    .append("scopeMethod", "get2D").append("scopeParameters", List.of("java.lang.String"))
                    .append("scopePath", "src/main/java/example/payment/Invoice.java")))
                    .doesNotThrowAnyException();
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

    private static IndexJob runningMetadata(RepositoryId repositoryId) {
        return new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true, Optional.empty(), false,
                IndexJobOperation.GIT_METADATA, Optional.of(GitEvidenceJob.metadata("main")), Optional.empty(),
                Optional.of(PreparationRequest.metadata(new PreparationRequestId(java.util.UUID.randomUUID().toString()), Optional.empty())),
                Optional.empty());
    }

    private static IndexJob admitRunningMetadata(MongoTemplate template, RepositoryId repositoryId) {
        MongoIndexJobStore jobs = new MongoIndexJobStore(template);
        jobs.admitMetadata(repositoryId,
                PreparationRequest.metadata(new PreparationRequestId(java.util.UUID.randomUUID().toString()), Optional.empty()), "main");
        return jobs.startNextAccepted().orElseThrow();
    }

    private static Document commitRow(String repositoryId, String historyId, long ordinal, String revision) {
        return new Document("repoId", repositoryId).append("historyId", historyId).append("ordinal", ordinal).append("revision", revision)
                .append("parents", List.of()).append("subject", "subject").append("committedAt", java.util.Date.from(Instant.now()));
    }

    private static void assertPreparing(org.springframework.data.mongodb.core.MongoTemplate template, String evidenceId) {
        assertThat(template.getCollection("git_evidence_manifests").find(new Document("evidenceId", evidenceId)).first().getString("state"))
                .isEqualTo("PREPARING");
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
