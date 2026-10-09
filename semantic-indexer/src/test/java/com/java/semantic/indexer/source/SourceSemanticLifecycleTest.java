package com.java.semantic.indexer.source;

import com.java.semantic.indexer.application.IndexerPreparationFacade;
import com.java.semantic.indexer.application.IndexerPreparationErrorMapper;
import com.java.semantic.indexer.index.IndexBuildInput;
import com.java.semantic.indexer.index.IndexLifecycleFixture;
import com.java.semantic.indexer.index.IndexSourceAccess;
import com.java.semantic.indexer.index.SemanticIndexJob;
import com.java.semantic.indexer.index.SqliteIndexBuilder;
import com.java.semantic.indexer.index.JavaSyntaxExtractor;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobDispatcher;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.PreparationRequestReusedException;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.index.IndexContract.Declaration;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSemanticLifecycleTest {
    @TempDir Path root;

    @Test
    void source_precondition_shared_request_identity_and_active_fence_are_durable() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            IndexLifecycleFixture indexes = indexes(source, Clock.systemUTC());
            String revision = source.commit("class A {}\n");
            PreparationRequestId absent = request();
            assertThatThrownBy(() -> indexes.coordinator.prepareSemanticIndex(source.git.repository, absent, RepositoryRevision.ofSha(revision)))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_NOT_CURRENT));
            assertThat(indexes.jobs.find(source.git.repository, absent)).isEmpty();
            PreparationRequestId original = request();
            SourcePreparationJob sourceAccepted = indexes.coordinator.prepareSource(source.git.repository, original, Optional.empty());
            assertThatThrownBy(() -> indexes.coordinator.prepareSemanticIndex(source.git.repository, request(), RepositoryRevision.ofSha(revision)))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            source.manager.execute(source.jobs.claimNext().orElseThrow());
            assertThatThrownBy(() -> indexes.coordinator.prepareSemanticIndex(source.git.repository, original, RepositoryRevision.ofSha(revision)))
                    .isInstanceOfSatisfying(PreparationRequestReusedException.class, reused -> assertThat(reused.jobId().value()).isEqualTo(sourceAccepted.jobId()));
            PreparationRequestId semanticRequest = request();
            SemanticIndexJob accepted = indexes.coordinator.prepareSemanticIndex(source.git.repository, semanticRequest, RepositoryRevision.ofSha(revision));
            assertThatThrownBy(() -> indexes.coordinator.prepareSource(source.git.repository, request(), Optional.empty()))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            IndexerPreparationFacade facade = new IndexerPreparationFacade(indexes.coordinator);
            Map<String, Object> byRequest = facade.getJob(Map.of("repositoryId", "orders", "requestId", semanticRequest.value()));
            assertThat(facade.getJob(Map.of("repositoryId", "orders", "jobId", accepted.jobId()))).isEqualTo(byRequest);
            assertThat(byRequest).containsEntry("operation", "prepare_semantic_index").containsEntry("revision", revision);
            assertThat(facade.getJob(Map.of("repositoryId", "orders", "requestId", original.value())))
                    .containsEntry("operation", "prepare_source");
        }
    }

    @Test
    void historical_admission_and_stale_execution_fail_without_accepting_or_building_replacement() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = source.commit("class A {}\n");
            source.prepare(a);
            IndexLifecycleFixture indexes = indexes(source, Clock.systemUTC());
            PreparationRequestId original = request();
            SemanticIndexJob accepted = indexes.coordinator.prepareSemanticIndex(source.git.repository, original, RepositoryRevision.ofSha(a));
            SemanticIndexJob running = indexes.jobs.claimNext().orElseThrow();
            // Represent a stale durable intent from before startup policy migration.
            String b = source.commit("class B {}\n");
            source.prepare(b);
            SemanticIndexJob failed = indexes.manager.execute(running);
            assertThat(failed.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(failed.failureCode()).contains("INDEX_NOT_CURRENT");
            assertThat(failed.jobId()).isEqualTo(accepted.jobId());
            assertThat(failed.requestId()).isEqualTo(original.value());
            assertThat(Files.exists(source.git.admin.resolve("index/staging").resolve(failed.indexGeneration()))).isFalse();
            PreparationRequestId refused = request();
            assertThatThrownBy(() -> indexes.coordinator.prepareSemanticIndex(source.git.repository, refused, RepositoryRevision.ofSha(a)))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_NOT_CURRENT));
            assertThat(indexes.jobs.find(source.git.repository, refused)).isEmpty();
            assertThat(indexes.publications.state(source.git.repository).revisions()).isEmpty();
            assertThat(source.state().current().orElseThrow().revision()).isEqualTo(b);
            assertThat(source.state().published()).containsKeys(a, b);
        }
    }

    @Test
    void current_only_conflict_has_safe_nonretryable_transport_advice() {
        IndexerPreparationErrorMapper.Failure failure = new IndexerPreparationErrorMapper()
                .map(new IndexFailure(IndexFailure.Code.INDEX_NOT_CURRENT, new IllegalStateException("private origin secret")));
        assertThat(failure.status()).isEqualTo(409);
        assertThat(failure.body()).containsEntry("code", "INDEX_NOT_CURRENT").containsEntry("retryable", false);
        assertThat(failure.body().get("message")).isEqualTo(IndexFailure.Code.INDEX_NOT_CURRENT.advice());
        assertThat(failure.body().toString()).doesNotContain("private origin secret");
    }

    @Test
    void atomic_write_residue_does_not_block_job_recovery_or_later_source_dispatch() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = source.commit("class A {}\n");
            source.prepare(revision);
            IndexLifecycleFixture indexes = indexes(source, Clock.systemUTC());
            PreparationRequestId indexRequest = request();
            SemanticIndexJob accepted = indexes.coordinator.prepareSemanticIndex(
                    source.git.repository, indexRequest, RepositoryRevision.ofSha(revision));
            Path jobDirectory = source.git.admin.resolve("index/jobs/orders");
            List<Path> residue = new ArrayList<>(atomicWriteResidue(jobDirectory, source.mapper.writeValueAsBytes(accepted)));

            IndexLifecycleFixture reopened = indexes(source, Clock.systemUTC());
            reopened.coordinator.validateStartup();
            reopened.jobs.recover(reopened.publications);
            assertThat(reopened.jobs.find(source.git.repository, indexRequest)).contains(accepted);
            SemanticIndexJob running = reopened.jobs.claimNext().orElseThrow();
            assertThat(running.phase()).isEqualTo(SemanticIndexJob.Phase.RUNNING);
            assertThat(running.jobId()).isEqualTo(accepted.jobId());
            assertThat(residue).allSatisfy(path -> assertThat(Files.exists(path)).isTrue());

            residue.addAll(atomicWriteResidue(jobDirectory, source.mapper.writeValueAsBytes(running)));
            IndexLifecycleFixture restarted = indexes(source, Clock.systemUTC());
            restarted.coordinator.validateStartup();
            restarted.jobs.recover(restarted.publications);
            SemanticIndexJob interrupted = restarted.jobs.find(source.git.repository, indexRequest).orElseThrow();
            assertThat(interrupted.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(interrupted.failureCode()).contains("WORKER_INTERRUPTED");
            assertThat(interrupted.requestId()).isEqualTo(indexRequest.value());
            assertThat(interrupted.jobId()).isEqualTo(accepted.jobId());
            assertThat(interrupted.indexGeneration()).isEqualTo(accepted.indexGeneration());
            assertThat(interrupted.publication()).isEmpty();
            assertThat(residue).allSatisfy(path -> assertThat(Files.exists(path)).isTrue());

            String nextRevision = source.commit("class B {}\n");
            PreparationRequestId sourceRequest = request();
            restarted.coordinator.prepareSource(source.git.repository, sourceRequest,
                    Optional.empty());
            SourceGarbageCollector collector = new SourceGarbageCollector(source.properties, source.registry,
                    source.publications, source.retention, Clock.systemUTC(), restarted.collector);
            IndexJobDispatcher dispatcher = restarted.dispatcher(source.manager, collector);
            try {
                dispatcher.dispatchOnce();
            } finally {
                dispatcher.stop();
            }
            SourcePreparationJob dispatched = source.jobs.find(source.git.repository, sourceRequest).orElseThrow();
            assertThat(dispatched.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
            assertThat(source.state().current().orElseThrow().revision()).isEqualTo(nextRevision);
        }
    }

    @Test
    void current_build_preserves_source_and_raw_lf_lines_and_capacity_failure_preserves_selection() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = source.commit("class A {\r void alpha() {}\r}\n"); source.prepare(a);
            IndexLifecycleFixture indexes = indexes(source, Clock.systemUTC());
            byte[] sourceBefore = Files.readAllBytes(source.git.published.resolve("orders/state.json"));
            SemanticIndexJob completed = build(source, indexes, a);
            IndexManifest manifest = completed.publication().orElseThrow().manifest();
            assertThat(manifest.coverage().complete()).isTrue();
            assertThat(manifest.coverage().declarations()).isEqualTo(2);
            Path database = artifact(source, completed).resolve("index.sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                    PreparedStatement statement = connection.prepareStatement("SELECT declaration_json FROM declarations WHERE name=?")) {
                statement.setString(1, "alpha");
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    Declaration declaration = source.mapper.readValue(rows.getString(1), Declaration.class);
                    assertThat(declaration.nameRange().startLine()).isEqualTo(1);
                    byte[] bytes = Files.readAllBytes(source.git.published.resolve("orders/revisions").resolve(a).resolve("tree/A.java"));
                    assertThat(new String(bytes, (int) declaration.nameRange().startByte(),
                            (int) (declaration.nameRange().endByte() - declaration.nameRange().startByte()), java.nio.charset.StandardCharsets.UTF_8))
                            .isEqualTo("alpha");
                }
            }
            source.properties.getSourceCapacity().setMaxManagedBytes(1);
            indexes.coordinator.prepareSemanticIndex(source.git.repository, request(), RepositoryRevision.ofSha(a));
            SemanticIndexJob failed = indexes.manager.execute(indexes.jobs.claimNext().orElseThrow());
            assertThat(failed.phase()).isEqualTo(SemanticIndexJob.Phase.FAILED);
            assertThat(failed.failureCode()).contains("CAPACITY_EXCEEDED");
            assertThat(indexes.publications.state(source.git.repository).revisions().get(a).selectedGeneration()).contains(completed.indexGeneration());
            assertThat(Files.readAllBytes(source.git.published.resolve("orders/state.json"))).containsExactly(sourceBefore);
            assertThat(source.state().current().orElseThrow().revision()).isEqualTo(a);
        }
    }

    @Test
    void committed_receipt_wins_over_a_later_capacity_refusal() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = source.commit("class A {}\n"); source.prepare(revision);
            IndexLifecycleFixture indexes = indexes(source, Clock.systemUTC());
            indexes.coordinator.prepareSemanticIndex(source.git.repository, request(), RepositoryRevision.ofSha(revision));
            SemanticIndexJob running = indexes.jobs.claimNext().orElseThrow();
            IndexBuildInput input = new IndexSourceAccess(source.publications, source.registry, source.properties)
                    .input(new SourceContext("orders", revision), running.indexGeneration());
            Path stage = source.git.admin.resolve("index/staging").resolve(running.indexGeneration());
            IndexManifest manifest = new SqliteIndexBuilder(source.mapper, new JavaSyntaxExtractor()).build(input, stage, () -> {});
            Receipt committed = indexes.publications.publish(running, stage, manifest);
            source.properties.getSourceCapacity().setMaxManagedBytes(1);
            SemanticIndexJob completed = indexes.manager.execute(running);
            assertThat(completed.phase()).isEqualTo(SemanticIndexJob.Phase.COMPLETE);
            assertThat(completed.publication()).contains(committed);
            assertThat(indexes.jobs.find(source.git.repository, new IndexJobId(running.jobId())).orElseThrow()).isEqualTo(completed);
        }
    }

    @Test
    void startup_rejects_cross_store_request_ambiguity_before_changing_execution_state() throws Exception {
        try (var source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = source.commit("class A {}\n");
            PreparationRequestId shared = request();
            source.service.prepareSource(source.git.repository, shared, Optional.of(RepositoryRevision.ofSha(revision)));
            source.manager.execute(source.jobs.claimNext().orElseThrow());
            var indexes = indexes(source, Clock.systemUTC());
            var input = new IndexSourceAccess(source.publications, source.registry, source.properties)
                    .input(new SourceContext("orders", revision), UUID.randomUUID().toString());
            indexes.jobs.admit(input, shared, Optional.empty());
            assertThatThrownBy(indexes.coordinator::validateStartup).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> indexes.coordinator.getJob(source.git.repository, Optional.empty(), Optional.of(shared)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(indexes.jobs.find(source.git.repository, shared).orElseThrow().phase()).isEqualTo(SemanticIndexJob.Phase.ACCEPTED);
        }
    }

    @Test
    void source_companion_recovers_each_durable_handoff_without_deleted_source_metadata() throws Exception {
        for (String crash : List.of("intent", "source_withdrawn", "source_deleted", "index_withdrawn", "database_deleted", "index_deleted", "companion_complete")) {
            try (var source = new SourceRetentionPublicationTest.Lifecycle(root.resolve(crash))) {
                String a = source.commit("class A {}\n"); source.prepare(a);
                Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
                var indexes = indexes(source, clock);
                var completed = build(source, indexes, a);
                String b = source.commit("class B {}\n"); source.prepare(b);
                source.writeRetired(Map.of(a, clock.instant().minus(SourceRetentionStore.RETENTION)));
                var intent = source.intent(a);
                source.retention.beginDeletion(intent);
                try (var channel = FileChannel.open(source.git.published.resolve("orders/read.lock"), StandardOpenOption.WRITE);
                        FileLock guard = channel.lock()) {
                    indexes.collector.beginSource(intent);
                    if (!crash.equals("intent")) assertThat(source.publications.withdraw(intent, clock.instant())).isTrue();
                }
                if (!List.of("intent", "source_withdrawn").contains(crash)) {
                    removeOwned(source.git.published.resolve("orders/revisions").resolve(a));
                    if (!crash.equals("source_deleted")) {
                        indexes.publications.withdraw(completed.publication().orElseThrow(), true);
                        if (crash.equals("database_deleted")) Files.delete(artifact(source, completed).resolve("index.sqlite"));
                        if (crash.equals("index_deleted") || crash.equals("companion_complete")) removeOwned(artifact(source, completed));
                        if (crash.equals("companion_complete")) assertThat(indexes.collector.finishSource(intent)).isTrue();
                    }
                }
                var reopened = indexes(source, clock);
                new SourceGarbageCollector(source.properties, source.registry, source.publications, source.retention, clock, reopened.collector).runIfDue();
                assertThat(source.retention.pendingDeletion(source.git.repository)).as(crash).isEmpty();
                assertThat(reopened.retention.pending(source.git.repository)).as(crash).isEmpty();
                assertThat(reopened.publications.state(source.git.repository).revisions()).as(crash).doesNotContainKey(a);
                assertThat(Files.exists(artifact(source, completed))).as(crash).isFalse();
                assertThat(source.state().current().orElseThrow().revision()).as(crash).isEqualTo(b);
                assertThat(Files.readString(source.git.published.resolve("orders/revisions").resolve(b).resolve("tree/A.java")))
                        .as(crash).isEqualTo("class B {}\n");
            }
        }
    }

    @Test
    void an_old_generation_read_defers_collection_and_selected_generation_survives() throws Exception {
        try (var source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String revision = source.commit("class A {}\n"); source.prepare(revision);
            var indexes = indexes(source, Clock.systemUTC());
            var old = build(source, indexes, revision);
            var selected = build(source, indexes, revision);
            try (var channel = FileChannel.open(source.git.published.resolve("orders/read.lock"), StandardOpenOption.READ);
                    FileLock guard = channel.lock(0, Long.MAX_VALUE, true)) {
                indexes.collector.runIfDue();
                assertThat(indexes.publications.state(source.git.repository).revisions().get(revision).generations())
                        .containsKeys(old.indexGeneration(), selected.indexGeneration());
                assertThat(Files.exists(artifact(source, old).resolve("index.sqlite"))).isTrue();
            }
            var reopened = indexes(source, Clock.systemUTC());
            reopened.collector.runIfDue();
            var retained = reopened.publications.state(source.git.repository).revisions().get(revision);
            assertThat(retained.generations()).containsOnlyKeys(selected.indexGeneration());
            assertThat(retained.selectedGeneration()).contains(selected.indexGeneration());
            assertThat(Files.exists(artifact(source, old))).isFalse();
            assertThat(Files.exists(artifact(source, selected).resolve("index.sqlite"))).isTrue();
        }
    }

    @Test
    void disabling_during_physical_deletion_preserves_remaining_bytes_and_republication_fence() throws Exception {
        try (var source = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = source.commit("class A {}\n"); source.prepare(a);
            Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            var indexes = indexes(source, clock);
            var indexed = build(source, indexes, a);
            String b = source.commit("class B {}\n"); source.prepare(b);
            source.writeRetired(Map.of(a, clock.instant().minus(SourceRetentionStore.RETENTION)));
            Path revision = source.git.published.resolve("orders/revisions").resolve(a);
            List<Path> files = List.of(revision.resolve("manifest.json"), revision.resolve("inventory.jsonl"), revision.resolve("tree/A.java"));
            // Model an operator disable immediately after the first actual unlink, without a scheduling race.
            var stopping = new com.java.semantic.repository.config.RepositoryProperties() {
                @Override public SourceRetentionConfig getSourceRetention() {
                    if (files.stream().anyMatch(path -> !Files.exists(path))) source.properties.getSourceRetention().setEnabled(false);
                    return source.properties.getSourceRetention();
                }
            };
            stopping.setSourcePublishedRoot(source.properties.getSourcePublishedRoot());
            new SourceGarbageCollector(stopping, source.registry, source.publications, source.retention, clock, indexes.collector).runIfDue();
            assertThat(files.stream().filter(Files::exists).count()).isEqualTo(2);
            assertThat(source.retention.pendingDeletion(source.git.repository)).isPresent();
            assertThat(indexes.retention.pending(source.git.repository)).isPresent();
            assertThat(Files.exists(artifact(source, indexed).resolve("index.sqlite"))).isTrue();
            source.service.prepareSource(source.git.repository, request(), Optional.of(RepositoryRevision.ofSha(a)));
            var refused = source.manager.execute(source.jobs.claimNext().orElseThrow());
            assertThat(refused.phase()).isEqualTo(com.java.semantic.indexer.job.SourcePreparationJob.Phase.FAILED);
            assertThat(source.state().current().orElseThrow().revision()).isEqualTo(b);
            new SourceGarbageCollector(source.properties, source.registry, source.publications, source.retention, clock, indexes.collector).runIfDue();
            assertThat(files.stream().filter(Files::exists).count()).isEqualTo(2);
            source.properties.getSourceRetention().setEnabled(true);
            new SourceGarbageCollector(source.properties, source.registry, source.publications, source.retention, clock, indexes.collector).runIfDue();
            assertThat(source.retention.pendingDeletion(source.git.repository)).isEmpty();
            assertThat(indexes.retention.pending(source.git.repository)).isEmpty();
            source.prepareHistorical(a);
            assertThat(source.state().current().orElseThrow().revision()).isEqualTo(b);
            assertThat(indexes.publications.state(source.git.repository).revisions()).doesNotContainKey(a);
        }
    }

    private static PreparationRequestId request() { return new PreparationRequestId(UUID.randomUUID().toString()); }
    private static List<Path> atomicWriteResidue(Path directory, byte[] record) throws IOException {
        Path partial = Files.createTempFile(directory, ".source-", ".tmp");
        Files.write(partial, Arrays.copyOf(record, record.length / 2));
        Path complete = Files.createTempFile(directory, ".source-", ".tmp");
        Files.write(complete, record);
        return List.of(partial, complete);
    }
    private static IndexLifecycleFixture indexes(SourceRetentionPublicationTest.Lifecycle source, Clock clock) throws Exception {
        return new IndexLifecycleFixture(source.properties, source.mapper, source.owner, source.jobs, source.publications, clock);
    }
    private static SemanticIndexJob build(SourceRetentionPublicationTest.Lifecycle source, IndexLifecycleFixture indexes, String revision) {
        indexes.coordinator.prepareSemanticIndex(source.git.repository, request(), RepositoryRevision.ofSha(revision));
        var completed = indexes.manager.execute(indexes.jobs.claimNext().orElseThrow());
        assertThat(completed.phase()).isEqualTo(SemanticIndexJob.Phase.COMPLETE);
        return completed;
    }
    private static Path artifact(SourceRetentionPublicationTest.Lifecycle source, SemanticIndexJob job) {
        return Path.of(source.properties.getIndexPublishedRoot()).resolve("orders/revisions").resolve(job.revision()).resolve(job.indexGeneration());
    }
    private static void removeOwned(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
