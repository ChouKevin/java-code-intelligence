package com.java.semantic.semantic.adapter.jdtls;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdtWorkspaceSessionTest {

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
                await(releaseFirst);
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
        JdtLsProcessFactory.LaunchHandle handle = new JdtLsProcessFactory.LaunchHandle(
                new TestProcess(), new TestLanguageServer(), new CompletableFuture<>(),
                new CompletableFuture<>(), new StderrRingBuffer(10));
        return new JdtWorkspaceSession(
                RepositoryId.of("order-service"), RepositoryRevision.ofSha("a".repeat(40)),
                handle, Duration.ofSeconds(1));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test operation was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test operation was interrupted", exception);
        }
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
        public void destroy() {
            // This test does not stop the process.
        }
    }
}
