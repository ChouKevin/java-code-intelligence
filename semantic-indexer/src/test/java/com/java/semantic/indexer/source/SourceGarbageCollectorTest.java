package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.IndexJobDispatcher;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SourceGarbageCollectorTest {
    @TempDir Path root;

    @Test
    void collects_only_non_current_revisions_at_the_thirty_day_boundary() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant retired = Instant.parse("2026-01-01T00:00:00Z");
            fixture.writeRetired(Map.of(a, retired));
            MutableClock clock = new MutableClock(retired.plus(SourceRetentionStore.RETENTION).minusNanos(1));
            collector(fixture, clock).runIfDue();
            assertThat(fixture.state().published()).containsKeys(a, b);
            clock.now = retired.plus(SourceRetentionStore.RETENTION);
            collector(fixture, clock).runIfDue();
            assertThat(fixture.state().published()).containsKey(b).doesNotContainKey(a);
            assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(b);
            assertThat(Files.exists(fixture.git.published.resolve("orders/revisions").resolve(a))).isFalse();
            assertThat(Files.readString(fixture.git.published.resolve("orders/revisions").resolve(b).resolve("tree/A.java"))).isEqualTo("B");
        }
    }

    @Test
    void busy_repo_and_disabled_collection_do_not_revoke_membership() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            MutableClock clock = new MutableClock(now);
            SourceGarbageCollector collector = collector(fixture, clock);
            try (FileChannel reader = FileChannel.open(fixture.git.published.resolve("orders/read.lock"), StandardOpenOption.READ);
                    FileLock guard = reader.lock(0, Long.MAX_VALUE, true)) {
                assertThat(guard.isShared()).isTrue();
                collector.runIfDue();
                assertThat(fixture.state().published()).containsKey(a);
            }
            clock.now = now.plus(fixture.properties.getSourceRetention().getInterval());
            fixture.properties.getSourceRetention().setEnabled(false);
            collector.runIfDue();
            assertThat(fixture.state().published()).containsKey(a);
            fixture.properties.getSourceRetention().setEnabled(true);
            collector.runIfDue();
            assertThat(fixture.state().published()).doesNotContainKey(a).containsKey(b);
        }
    }

    @Test
    void queued_preparation_precedes_due_collection() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            fixture.service.prepareSource(fixture.git.repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.of(RepositoryRevision.ofSha(a)));
            IndexJobDispatcher dispatcher = new IndexJobDispatcher(fixture.jobs, new IndexJobExecutor(fixture.manager),
                    fixture.publications, collector(fixture, new MutableClock(now)));
            try {
                dispatcher.dispatchOnce();
                assertThat(fixture.state().current().orElseThrow().revision()).isEqualTo(a);
                assertThat(fixture.state().published()).containsKeys(a, b);
                dispatcher.dispatchOnce();
                assertThat(fixture.state().published()).containsKeys(a, b);
            } finally { dispatcher.stop(); }
        }
    }

    @Test
    void scan_uses_fixed_delay_and_does_not_tightly_retry_failed_deletion() throws Exception {
        try (SourceRetentionPublicationTest.Lifecycle fixture = new SourceRetentionPublicationTest.Lifecycle(root)) {
            String a = fixture.commit("A"); fixture.prepare(a);
            String b = fixture.commit("B"); fixture.prepare(b);
            Instant now = Instant.now();
            fixture.writeRetired(Map.of(a, now.minus(SourceRetentionStore.RETENTION)));
            MutableClock clock = new MutableClock(now);
            Path tree = fixture.git.published.resolve("orders/revisions").resolve(a).resolve("tree");
            Files.createSymbolicLink(tree.resolve("unsafe"), root.resolve("unrelated"));
            SourceGarbageCollector collector = collector(fixture, clock);
            collector.runIfDue();
            assertThat(fixture.state().published()).containsKey(a);
            Files.delete(tree.resolve("unsafe"));
            clock.now = now.plus(fixture.properties.getSourceRetention().getInterval()).minusNanos(1);
            collector.runIfDue();
            assertThat(fixture.state().published()).containsKey(a);
            clock.now = now.plus(fixture.properties.getSourceRetention().getInterval());
            collector.runIfDue();
            assertThat(fixture.state().published()).doesNotContainKey(a).containsKey(b);
        }
    }

    static SourceGarbageCollector collector(SourceRetentionPublicationTest.Lifecycle fixture, Clock clock) {
        return new SourceGarbageCollector(fixture.properties, fixture.registry, fixture.publications, fixture.retention, clock);
    }
    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
}
