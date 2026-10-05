package com.java.semantic.indexer.source;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;

class SourceGcRecoveryTest {
    @TempDir Path root;

    @Test
    void resume_intent_before_after_withdrawal_and_after_partial_delete() throws Exception {
        for (String stage : List.of("intent", "withdrawn", "partial")) {
            try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root.resolve(stage))) {
                String a = fixture.commit("A"); fixture.prepare(a);
                String b = fixture.commit("B"); fixture.prepare(b);
                Instant now = Instant.now();
                fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
                SourceRetentionStore.DeleteIntent intent = fixture.intent(a);
                fixture.retention.beginDeletion(intent);
                if (!stage.equals("intent")) assertThat(fixture.publications.withdraw(intent, now)).isTrue();
                Path revision = fixture.git.published.resolve("orders/revisions").resolve(a);
                if (stage.equals("partial")) Files.delete(revision.resolve("tree/A.java"));
                SourceRetentionStore reopened = new SourceRetentionStore(fixture.properties, fixture.mapper, fixture.registry);
                new SourceGarbageCollector(fixture.properties, fixture.registry, fixture.publications, reopened,
                        Clock.fixed(now, ZoneOffset.UTC)).runIfDue();
                assertThat(Files.exists(revision)).isFalse();
                assertThat(reopened.pendingDeletion(fixture.git.repository)).isEmpty();
                assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(b);
                assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(b).resolve("tree/A.java"))).isEqualTo("B");
            }
        }
    }

    @Test
    void disabled_collection_never_resumes_withdrawn_intent() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            SourceRetentionStore.DeleteIntent intent = fixture.intent(a);
            fixture.retention.beginDeletion(intent);
            assertThat(fixture.publications.withdraw(intent, now)).isTrue();
            fixture.properties.getSourceRetention().setEnabled(false);
            SourceGarbageCollector collector = SourceGarbageCollectorTest.collector(fixture, Clock.fixed(now, ZoneOffset.UTC));
            collector.runIfDue();
            assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(a).resolve("tree/A.java"))).isEqualTo("A");
            assertThat(fixture.retention.pendingDeletion(fixture.git.repository)).contains(intent);
            fixture.properties.getSourceRetention().setEnabled(true);
            collector.runIfDue();
            assertThat(fixture.retention.pendingDeletion(fixture.git.repository)).isEmpty();
            assertThat(fixture.state().published()).containsKey(b).doesNotContainKey(a);
        }
    }

    @Test
    void unmarked_orphan_symlink_and_changed_origin_are_never_reclaimed() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            Path revisions = fixture.git.published.resolve("orders/revisions");
            Path orphan = Files.createDirectory(revisions.resolve("f".repeat(40)));
            Files.writeString(orphan.resolve("evidence"), "orphan");
            Path unrelated = root.resolve("unrelated"); Files.writeString(unrelated, "outside");
            Path link = revisions.resolve(a).resolve("tree/link"); Files.createSymbolicLink(link, unrelated);
            SourceGarbageCollectorTest.collector(fixture, Clock.fixed(now, ZoneOffset.UTC)).runIfDue();
            assertThat(fixture.state().published()).containsKeys(a, b);
            assertThat(Files.readString(orphan.resolve("evidence"))).isEqualTo("orphan");
            assertThat(Files.readString(unrelated)).isEqualTo("outside");
            Files.delete(link);
            fixture.properties.getRepositories().get("orders").setUrl(root.resolve("different-origin").toUri().toString());
            SourceGarbageCollectorTest.collector(fixture, Clock.fixed(now, ZoneOffset.UTC)).runIfDue();
            assertThat(Files.readString(revisions.resolve(a).resolve("tree/A.java"))).isEqualTo("A");
            assertThat(Files.readString(revisions.resolve(b).resolve("tree/A.java"))).isEqualTo("B");
        }
    }

    @Test
    void pending_delete_blocks_same_sha_republication() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            fixture.retention.beginDeletion(fixture.intent(a));
            fixture.service.prepareSource(fixture.git.repository, new PreparationRequestId(UUID.randomUUID().toString()), Optional.of(RepositoryRevision.ofSha(a)));
            SourcePreparationJob result = fixture.manager.execute(fixture.jobs.claimNext().orElseThrow());
            assertThat(result.phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
            assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(b);
            assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(a).resolve("tree/A.java"))).isEqualTo("A");
        }
    }

    @Test
    void gc_events_correlate_partial_result_without_disclosing_exception_secrets() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SourceGarbageCollector.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
        String secret = "sentinel-private-url-token";
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            String c = fixture.commit("C"); fixture.prepare(c);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION), b, now.minus(SourceRetentionStore.RETENTION)));
            Files.createSymbolicLink(fixture.git.published.resolve("orders/revisions").resolve(b).resolve("tree/unsafe"), root.resolve(secret));
            SourceGarbageCollectorTest.collector(fixture, Clock.fixed(now, ZoneOffset.UTC)).runIfDue();
            List<String> logs = capture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            String start = logs.stream().filter(message -> message.contains("event=source_gc_start")).findFirst().orElseThrow();
            String run = start.substring(start.indexOf("gcRunId=") + 8).split(" ")[0];
            assertThat(logs).anyMatch(message -> message.contains("event=source_gc_deleted") && message.contains("gcRunId=" + run)
                    && message.contains("revision=" + a) && message.contains("deletedLogicalBytes="));
            assertThat(logs).anyMatch(message -> message.contains("event=source_gc_failure") && message.contains("gcRunId=" + run)
                    && message.contains("errorCode=GC_OPERATION_FAILED") && message.contains("stage="));
            assertThat(logs).anyMatch(message -> message.contains("event=source_gc_end") && message.contains("result=partial"));
            assertThat(String.join("\n", logs)).doesNotContain(secret, root.toString());
            assertThat(fixture.state().published()).containsKeys(b, c).doesNotContainKey(a);
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
}
