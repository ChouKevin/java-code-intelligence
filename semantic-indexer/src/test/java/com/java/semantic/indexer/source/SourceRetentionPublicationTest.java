package com.java.semantic.indexer.source;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceRetentionPublicationTest {
    @TempDir Path root;

    @Test
    void republishing_a_revision_restarts_its_retirement_window() throws Exception {
        try (Lifecycle fixture = new Lifecycle(root)) {
            String a = fixture.commit("A");
            fixture.prepare(a);
            String b = fixture.commit("B");
            fixture.prepare(b);
            Instant first = fixture.retention.reconcile(fixture.git.repository, fixture.state(), Instant.now()).get(a);
            fixture.prepare(a);
            assertThat(fixture.retention.reconcile(fixture.git.repository, fixture.state(), Instant.now())).doesNotContainKey(a);
            fixture.prepare(b);
            Instant second = fixture.state().current().orElseThrow().publishedAt();
            assertThat(fixture.retention.reconcile(fixture.git.repository, fixture.state(), Instant.now()))
                    .containsEntry(a, second).doesNotContainKey(b);
            assertThat(second).isAfterOrEqualTo(first);
        }
    }

    @Test
    void missing_post_publication_timestamp_gets_a_fresh_full_window() throws Exception {
        try (Lifecycle fixture = new Lifecycle(root)) {
            String a = fixture.commit("A");
            fixture.prepare(a);
            String b = fixture.commit("B");
            fixture.prepare(b);
            fixture.writeRetired(Map.of());
            Instant observed = Instant.now().plusSeconds(86400);
            assertThat(fixture.retention.reconcile(fixture.git.repository, fixture.state(), observed)).containsEntry(a, observed);
            assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(b);
        }
    }

    @Test
    void post_publication_retirement_write_limit_failure_does_not_fail_committed_publication() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SourcePublicationStore.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start(); logger.addAppender(capture);
        try (Lifecycle fixture = new Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B");
            SourceRetentionStore.RetentionState empty = new SourceRetentionStore.RetentionState(1, "orders",
                    fixture.registry.origin(fixture.git.repository), Map.of());
            int emptyBytes = fixture.mapper.writeValueAsBytes(empty).length;
            SourceRetentionStore.RetentionState one = new SourceRetentionStore.RetentionState(1, "orders",
                    empty.originFingerprint(), Map.of("0".repeat(40), Instant.EPOCH));
            int entryBytes = fixture.mapper.writeValueAsBytes(one).length - emptyBytes + 1;
            int count = (4 * 1024 * 1024 - emptyBytes + 1) / entryBytes;
            Map<String, Instant> entries = new HashMap<>();
            for (int index = 0; index < count; index++) {
                String suffix = Integer.toHexString(index);
                entries.put("0".repeat(40 - suffix.length()) + suffix, Instant.EPOCH);
            }
            fixture.writeRetired(entries);
            Path record = fixture.git.admin.resolve("repositories/orders/retention.json");
            byte[] before = Files.readAllBytes(record);
            fixture.prepare(b);
            assertThat(capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).anyMatch(message ->
                    message.contains("event=source_retirement_record_failed") && message.contains("revision=" + b)
                            && message.contains("errorCode=RETIREMENT_WRITE_FAILED"));
            assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(b);
            assertThat(fixture.state().preparation().phase()).isEqualTo(SourceRepositoryState.PreparationPhase.COMPLETE);
            assertThat(fixture.state().published()).containsKeys(a, b);
            assertThat(Files.readAllBytes(record)).isEqualTo(before);
            assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(a).resolve("tree/A.java"))).isEqualTo("A");
            assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(b).resolve("tree/A.java"))).isEqualTo("B");
            Instant observed = Instant.now().plusSeconds(86400);
            assertThat(fixture.retention.reconcile(fixture.git.repository, fixture.state(), observed))
                    .containsEntry(a, observed).doesNotContainKey(b);
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

    @Test
    void withdraw_preserves_current_and_latest_preparation_status() throws Exception {
        try (Lifecycle fixture = new Lifecycle(root)) {
            String a = fixture.commit("A");
            fixture.prepare(a);
            String b = fixture.commit("B");
            fixture.prepare(b);
            Instant retired = Instant.now().minus(SourceRetentionStore.RETENTION);
            fixture.writeRetired(Map.of(a, retired));
            fixture.service.prepareSource(fixture.git.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.of(RepositoryRevision.ofSha(b)));
            SourceRepositoryState before = fixture.state();
            SourceRetentionStore.DeleteIntent intent = fixture.intent(a);
            fixture.retention.beginDeletion(intent);
            assertThat(fixture.publications.withdraw(intent, retired.plus(SourceRetentionStore.RETENTION))).isTrue();
            assertThat(fixture.state().current()).isEqualTo(before.current());
            assertThat(fixture.state().preparation()).isEqualTo(before.preparation());
            assertThat(fixture.state().published()).containsKey(b).doesNotContainKey(a);
        }
    }

    @Test
    void registered_unprepared_repository_has_stable_lock_and_missing_lifecycle_fails_closed() throws Exception {
        try (Lifecycle fixture = new Lifecycle(root)) {
            assertThat(fixture.state().current()).isEmpty();
            Path lock = fixture.git.published.resolve("orders/read.lock");
            assertThat(Files.isRegularFile(lock)).isTrue();
            Files.delete(lock);
            assertThatThrownBy(() -> fixture.publications.initializeLifecycle(fixture.git.repository))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void registration_does_not_adopt_populated_bound_storage_without_lifecycle() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            RepositoryProperties properties = SourcePreparationPublicationTest.properties(fixture);
            try (DurableSourceFiles owner = new DurableSourceFiles(fixture.admin)) {
                RepositoryRegistry registry = new RepositoryRegistry(properties);
                registry.bindAll();
                Path evidence = fixture.published.resolve("orders/unknown");
                Files.writeString(evidence, "not lifecycle data");
                assertThatThrownBy(() -> SourceLifecycleFixture.publisher(registry, properties,
                        JsonMapper.builder().build(), owner).publish()).isInstanceOf(IllegalStateException.class);
                assertThat(Files.readString(evidence)).isEqualTo("not lifecycle data");
            }
        }
    }

    static final class Lifecycle implements AutoCloseable {
        final LocalSourceFixture git;
        final DurableSourceFiles owner;
        final ObjectMapper mapper = JsonMapper.builder().build();
        final RepositoryProperties properties;
        final RepositoryRegistry registry;
        final FileSourceJobStore jobs;
        final SourceRetentionStore retention;
        final SourcePublicationStore publications;
        final SourcePreparationService service;
        final RepositorySourceManager manager;
        Lifecycle(Path root) throws Exception {
            git = new LocalSourceFixture(root);
            properties = SourcePreparationPublicationTest.properties(git);
            owner = new DurableSourceFiles(git.admin);
            registry = new RepositoryRegistry(properties);
            jobs = new FileSourceJobStore(properties, mapper, owner);
            retention = new SourceRetentionStore(properties, mapper, registry);
            publications = new SourcePublicationStore(properties, mapper, owner, jobs, retention);
            new ConfiguredRepositoryPublisher(registry, properties, mapper, owner, publications).publish();
            RepositoryRevisionResolver resolver = new RepositoryRevisionResolver(registry, properties);
            manager = new RepositorySourceManager(jobs, resolver, new JGitRevisionExporter(resolver, registry, mapper),
                    publications, registry, properties);
            service = new SourcePreparationService(jobs, publications, registry);
        }
        String commit(String text) throws Exception {
            return git.commit("A.java", text.getBytes(StandardCharsets.UTF_8), text);
        }
        void prepare(String revision) {
            service.prepareSource(git.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.of(RepositoryRevision.ofSha(revision)));
            SourcePreparationJob result = manager.execute(jobs.claimNext().orElseThrow());
            assertThat(result.phase()).isEqualTo(SourcePreparationJob.Phase.COMPLETE);
        }
        SourceRepositoryState state() { return publications.state(git.repository); }
        void writeRetired(Map<String, Instant> entries) throws Exception {
            DurableSourceFiles.atomicBytes(git.admin.resolve("repositories/orders/retention.json"),
                    mapper.writeValueAsBytes(new SourceRetentionStore.RetentionState(1, "orders", registry.origin(git.repository), entries)),
                    4L * 1024 * 1024, DurableSourceFiles.Visibility.PRIVATE);
        }
        SourceRetentionStore.DeleteIntent intent(String revision) {
            return new SourceRetentionStore.DeleteIntent(1, "orders", revision, registry.origin(git.repository),
                    state().published().get(revision).manifestDigest(), UUID.randomUUID().toString());
        }
        @Override public void close() throws Exception { owner.close(); git.close(); }
    }
}
