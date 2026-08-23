package com.java.semantic.semantic.adapter.jdtls;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.support.ConcurrencyTestSupport;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdtWorkspaceSessionTest {

    @ParameterizedTest
    @EnumSource(WorkspaceActivityKind.class)
    void should_keep_every_activity_kind_from_eviction_until_its_idempotent_lease_is_closed(
            WorkspaceActivityKind kind) {
        JdtWorkspaceSession session = session();

        WorkspaceActivityLease lease = session.acquireActivity(kind, "test");

        assertThat(session.tryBeginEviction()).isFalse();

        lease.close();
        lease.close();

        assertThat(session.tryBeginEviction()).isTrue();
    }

    @Test
    void should_evict_only_ready_alive_activity_free_sessions_after_the_idle_deadline() {
        MutableTicker ticker = new MutableTicker();
        JdtWorkspaceSession session = session(ticker);
        Duration idleTimeout = Duration.ofMinutes(30);

        ticker.advance(Duration.ofMinutes(40));
        assertThat(session.markReady()).isTrue();

        ticker.advance(Duration.ofMinutes(29));
        assertThat(session.tryBeginIdleEviction(ticker.readNanos(), idleTimeout)).isFalse();

        WorkspaceActivityLease lease = session.acquireActivity(WorkspaceActivityKind.INDEXING, "test");
        ticker.advance(Duration.ofMinutes(40));
        assertThat(session.tryBeginIdleEviction(ticker.readNanos(), idleTimeout)).isFalse();

        lease.close();
        ticker.advance(Duration.ofMinutes(29));

        assertThat(session.tryBeginIdleEviction(ticker.readNanos(), idleTimeout)).isFalse();

        ticker.advance(Duration.ofMinutes(1));

        assertThat(session.tryBeginIdleEviction(ticker.readNanos(), idleTimeout)).isTrue();
    }

    @Test
    void should_not_regress_last_used_when_an_older_touch_publishes_after_a_newer_touch()
            throws Exception {
        LatchingTicker ticker = new LatchingTicker();
        JdtWorkspaceSession session = session(ticker);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> olderTouch = executor.submit(session::touch);
            assertThat(ticker.awaitOlderSample(Duration.ofSeconds(1))).isTrue();

            session.touch();
            ticker.releaseOlderTouch();
            olderTouch.get(1, TimeUnit.SECONDS);

            assertThat(session.lastUsedNanos()).isEqualTo(20L);
        } finally {
            ticker.releaseOlderTouch();
            executor.shutdownNow();
        }
    }

    @Test
    void should_serialize_same_uri_operations_until_the_first_operation_completes() throws Exception {
        JdtWorkspaceSession session = session();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondContendedForLock = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> session.withDocumentUri("file:///Order.java", () -> {
                firstEntered.countDown();
                ConcurrencyTestSupport.await(releaseFirst, Duration.ofSeconds(1));
                return "first";
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(session.withDocumentUri("file:///Other.java", () -> "other")).isEqualTo("other");
            assertThat(session.tryBeginEviction()).isFalse();
            session.setDocumentUriLockContentionObserver(uri -> secondContendedForLock.countDown());

            Future<String> second = executor.submit(() -> session.withDocumentUri("file:///Order.java", () -> {
                secondEntered.countDown();
                return "second";
            }));
            assertThat(secondContendedForLock.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(secondEntered.getCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_reject_calls_and_document_lifecycles_after_invalidation() {
        JdtWorkspaceSession session = session();
        AtomicBoolean requestInvoked = new AtomicBoolean();
        session.invalidate();

        assertThatThrownBy(() -> session.call("late", server -> {
            requestInvoked.set(true);
            return CompletableFuture.completedFuture("late");
        })).isInstanceOf(JdtWorkspaceSession.JdtWorkspaceClosingException.class);
        assertThatThrownBy(() -> session.withDocumentUri("file:///Order.java", () -> "late"))
                .isInstanceOf(JdtWorkspaceSession.JdtWorkspaceClosingException.class);

        assertThat(requestInvoked).isFalse();
    }

    @Test
    void should_log_a_safe_confirmed_stop_event() {
        JdtWorkspaceSession session = session();
        Logger logger = (Logger) LoggerFactory.getLogger(JdtWorkspaceSession.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            session.stop();

            List<ILoggingEvent> events = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=jdtls-process outcome=confirmed-stop"))
                    .toList();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("repoId=order-service")
                        .doesNotContain("file:", "/tmp/", "jsonrpc", "-D");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_expose_the_launched_process_identifier_for_package_local_observation() {
        JdtWorkspaceSession session = session();

        assertThat(session.processId()).isEqualTo(4_321L);
    }

    private JdtWorkspaceSession session() {
        return session(System::nanoTime);
    }

    private JdtWorkspaceSession session(JdtMonotonicTicker ticker) {
        JdtLsProcessFactory.LaunchHandle handle = new JdtLsProcessFactory.LaunchHandle(
                new TestProcess(), new TestLanguageServer(), new CompletableFuture<>(),
                new CompletableFuture<>(), new StderrRingBuffer(10));
        return new JdtWorkspaceSession(
                RepositoryId.of("order-service"), RepositoryRevision.ofSha("a".repeat(40)),
                handle, Duration.ofSeconds(1), ticker);
    }

    private static final class TestLanguageServer implements JdtLsLanguageServer {

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult());
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            // This test does not issue protocol requests.
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            throw new UnsupportedOperationException("not used by URI locking test");
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException("not used by URI locking test");
        }

        @Override
        public CompletableFuture<JdtLsBuildWorkspaceStatus> buildWorkspace(Boolean forceRebuild) {
            return CompletableFuture.completedFuture(JdtLsBuildWorkspaceStatus.SUCCEED);
        }
    }

    private static final class TestProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);

        @Override
        public long pid() {
            return 4_321L;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void destroy() {
            // This test does not stop the process.
        }
    }

    private static final class MutableTicker implements JdtMonotonicTicker {

        private final AtomicLong currentNanos = new AtomicLong();

        @Override
        public long readNanos() {
            return currentNanos.get();
        }

        void advance(Duration duration) {
            currentNanos.addAndGet(duration.toNanos());
        }
    }

    private static final class LatchingTicker implements JdtMonotonicTicker {

        private final AtomicInteger readCount = new AtomicInteger();
        private final CountDownLatch olderSampled = new CountDownLatch(1);
        private final CountDownLatch releaseOlderTouch = new CountDownLatch(1);

        @Override
        public long readNanos() {
            int readIndex = readCount.getAndIncrement();
            if (readIndex == 0) {
                return 0L;
            }
            if (readIndex == 1) {
                olderSampled.countDown();
                awaitRelease();
                return 10L;
            }
            return 20L;
        }

        boolean awaitOlderSample(Duration timeout) throws InterruptedException {
            return olderSampled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void releaseOlderTouch() {
            releaseOlderTouch.countDown();
        }

        private void awaitRelease() {
            try {
                if (!releaseOlderTouch.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("older touch was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("older touch was interrupted", exception);
            }
        }
    }
}
