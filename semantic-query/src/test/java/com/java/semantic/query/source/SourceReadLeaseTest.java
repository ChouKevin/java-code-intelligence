package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceReadLeaseTest {
    @TempDir Path root;

    @Test
    void reader_keeps_exclusive_gc_peer_out_until_source_operation_finishes() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(root);
        fixture.file("A.java", "exact 🙂\r\n");
        fixture.publish(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        RepositorySourcePort reader = paused(fixture.service(), entered, resume, false);
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), reader, 2);
        try (ExecutorService executor = Executors.newSingleThreadExecutor(); Peer peer = new Peer(fixture, root.resolve("peer"))) {
            Future<SourceResult> result = executor.submit(() -> facade.readSource(request(fixture)));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            peer.start();
            peer.assertBlocked();
            resume.countDown();
            assertThat(result.get(2, TimeUnit.SECONDS).content()).isEqualTo("exact 🙂\r\n");
            peer.assertAcquired();
        } finally { resume.countDown(); }
    }

    @Test
    void last_reader_releases_shared_lock_after_success_failure_or_cancellation() throws Exception {
        for (String outcome : List.of("success", "failure", "cancellation")) {
            SourceFilesystemFixture fixture = new SourceFilesystemFixture(root.resolve(outcome));
            fixture.file("A.java", "bytes");
            fixture.publish(Optional.empty());
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), paused(fixture.service(), entered, resume,
                    outcome.equals("failure")), 2);
            try (ExecutorService executor = Executors.newSingleThreadExecutor(); Peer peer = new Peer(fixture, root.resolve(outcome + "-peer"))) {
                AdmittedSourceRevision first = fixture.admit();
                try {
                    Future<SourceResult> result = executor.submit(() -> facade.readSource(request(fixture)));
                    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                    peer.start();
                    first.close();
                    first.close();
                    peer.assertBlocked();
                    if (outcome.equals("cancellation")) result.cancel(true);
                    else resume.countDown();
                    if (outcome.equals("success")) assertThat(result.get(2, TimeUnit.SECONDS).content()).isEqualTo("bytes");
                    if (outcome.equals("failure")) assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(SourceQueryException.class);
                    peer.assertAcquired();
                } finally { first.close(); resume.countDown(); }
            }
        }
    }

    @Test
    void invisible_repo_is_rejected_before_lock_access() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(root);
        fixture.publish(Optional.empty());
        Files.delete(fixture.root.resolve("sample/read.lock"));
        SourceAccessProperties hidden = new SourceAccessProperties(fixture.root, fixture.properties.rgExecutable(), List.of(),
                65536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        LocalSourceRevisionCatalog catalog = new LocalSourceRevisionCatalog(hidden, fixture.mapper, fixture.locks);
        assertThatThrownBy(() -> catalog.admit(new SourceContext("sample", SourceFilesystemFixture.SHA)))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.REPOSITORY_NOT_FOUND));
    }

    @Test
    void missing_or_symlink_lock_fails_closed_for_visible_repository() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(root);
        fixture.file("A.java", "bytes");
        fixture.publish(Optional.empty());
        Path lock = fixture.root.resolve("sample/read.lock");
        Files.delete(lock);
        assertThatThrownBy(() -> fixture.catalog().admit(fixture.context)).isInstanceOfSatisfying(SourceQueryException.class,
                error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        Path outside = root.resolve("outside-lock");
        Files.createFile(outside);
        Files.createSymbolicLink(lock, outside);
        assertThatThrownBy(() -> fixture.catalog().getContext(new ContextRequest("sample", Optional.empty())))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
    }

    @Test
    void admission_lock_wait_respects_operation_deadline_and_does_not_leak_a_channel() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(root);
        fixture.file("A.java", "after exclusive release");
        fixture.publish(Optional.empty());
        try (Peer peer = new Peer(fixture, root.resolve("exclusive-peer"))) {
            peer.start();
            peer.assertAcquired();
            assertThatThrownBy(() -> SourceOperationDeadline.within(Duration.ofMillis(100),
                    () -> fixture.catalog().admit(fixture.context))).isInstanceOfSatisfying(SourceQueryException.class,
                    error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
        }
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            assertThat(fixture.service().readSource(admitted, request(fixture)).content()).isEqualTo("after exclusive release");
        }
    }

    private static ReadSourceRequest request(SourceFilesystemFixture fixture) {
        return new ReadSourceRequest(fixture.context, "A.java", 1, 200, Optional.empty());
    }
    private static RepositorySourcePort paused(RepositorySourcePort delegate, CountDownLatch entered, CountDownLatch resume, boolean fail) {
        return new RepositorySourcePort() {
            public FileCollection listFiles(AdmittedSourceRevision admitted, FileListRequest request) { return delegate.listFiles(admitted, request); }
            public TextSearchResult searchText(AdmittedSourceRevision admitted, TextSearchRequest request) { return delegate.searchText(admitted, request); }
            public SourceResult readSource(AdmittedSourceRevision admitted, ReadSourceRequest request) {
                entered.countDown();
                try {
                    if (!resume.await(1500, TimeUnit.MILLISECONDS)) throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT);
                }
                if (fail) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
                return delegate.readSource(admitted, request);
            }
        };
    }
    private static final class Peer implements AutoCloseable {
        final Path directory;
        final Path lock;
        Process process;
        Peer(SourceFilesystemFixture fixture, Path directory) throws Exception {
            this.directory = directory;
            this.lock = fixture.root.resolve("sample/read.lock");
            Files.createDirectories(directory);
        }
        void start() throws Exception {
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                    System.getProperty("java.class.path"), SourceReadLockPeer.class.getName(), "exclusive", lock.toString(),
                    directory.resolve("ready").toString(), directory.resolve("release").toString(), "5000")
                    .redirectErrorStream(true).redirectOutput(directory.resolve("output").toFile()).start();
            await(directory.resolve("ready.attempted"));
        }
        void assertBlocked() throws Exception {
            Thread.sleep(100);
            assertThat(Files.exists(directory.resolve("ready"))).isFalse();
            assertThat(process.isAlive()).isTrue();
        }
        void assertAcquired() throws Exception { await(directory.resolve("ready")); }
        private void await(Path path) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!Files.exists(path) && process.isAlive() && System.nanoTime() - deadline < 0) Thread.sleep(5);
            assertThat(Files.exists(path)).as("peer signal %s", path.getFileName()).isTrue();
        }
        public void close() throws Exception {
            if (java.util.Objects.nonNull(process)) {
                Files.writeString(directory.resolve("release"), "release");
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
                assertThat(process.exitValue()).isZero();
            }
        }
    }
}
