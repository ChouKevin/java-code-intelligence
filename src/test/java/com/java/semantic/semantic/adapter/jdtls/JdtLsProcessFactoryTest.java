package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class JdtLsProcessFactoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void should_build_exact_command_and_initialize_required_capabilities_when_launching() throws Exception {
        Path home = createJdtLsHome();
        Path workspaceRoot = Files.createDirectories(tempDirectory.resolve("repository"));
        Path workspaceData = tempDirectory.resolve("workspace-data");
        JdtLsProperties properties = properties(home);
        TestProcess process = new TestProcess(new ByteArrayInputStream(new byte[0]));
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<InitializeParams> capturedInitialize = new AtomicReference<>();
        CountDownLatch initialized = new CountDownLatch(1);
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                capturedInitialize.set(invocation.getArgument(0));
                return CompletableFuture.completedFuture(null);
            }
            if ("initialized".equals(invocation.getMethod().getName())) {
                initialized.countDown();
                return null;
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties,
                command -> {
                    capturedCommand.set(List.copyOf(command));
                    return process;
                },
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(
                        server, CompletableFuture.completedFuture(null)));
        JdtLanguageClient client = mock(JdtLanguageClient.class);

        JdtLsProcessFactory.LaunchHandle handle = factory.launch(workspaceRoot, workspaceData, client);

        assertThat(capturedCommand.get()).containsExactly(
                "java",
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=ALL",
                "-Xmx768m",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", home.resolve("plugins/org.eclipse.equinox.launcher_test.jar").toString(),
                "-configuration", home.resolve("config_linux").toString(),
                "-data", workspaceData.toString());
        assertThat(initialized.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.process()).isSameAs(process);
        assertThat(handle.languageServer()).isSameAs(server);
        assertThat(handle.stderrDrain().get(1, TimeUnit.SECONDS)).isNull();
        InitializeParams initializeParams = capturedInitialize.get();
        assertThat(initializeParams.getRootUri()).isEqualTo(workspaceRoot.toUri().toString());
        ClientCapabilities capabilities = initializeParams.getCapabilities();
        assertThat(capabilities.getTextDocument().getCallHierarchy()).isNotNull();
        assertThat(capabilities.getTextDocument().getDefinition()).isNotNull();
        assertThat(capabilities.getTextDocument().getTypeDefinition()).isNotNull();
        assertThat(capabilities.getTextDocument().getDocumentSymbol()).isNotNull();
        assertThat(capabilities.getTextDocument().getSynchronization()).isNotNull();
        assertThat(capabilities.getWorkspace().getSymbol()).isNotNull();
    }

    @Test
    void should_declare_custom_notifications_and_accept_registration_when_client_is_inspected()
            throws Exception {
        Method statusMethod = JdtLanguageClient.class.getMethod("languageStatus", StatusReport.class);
        Method eventMethod = JdtLanguageClient.class.getMethod("languageEvent", Object.class);
        JdtLanguageClient client = mock(JdtLanguageClient.class, CALLS_REAL_METHODS);

        Future<Void> registration = client.registerCapability(new RegistrationParams(List.of()));

        assertThat(statusMethod.getAnnotation(JsonNotification.class).value()).isEqualTo("language/status");
        assertThat(eventMethod.getAnnotation(JsonNotification.class).value())
                .isEqualTo("language/eventNotification");
        assertThat(registration.get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void should_release_process_and_listener_when_initialize_fails() throws Exception {
        Path home = createJdtLsHome();
        TestProcess process = new TestProcess(new ByteArrayInputStream(new byte[0]));
        CompletableFuture<Void> listener = new CompletableFuture<>();
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.failedFuture(new IllegalStateException("initialize failed"));
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(server, listener));

        assertThatThrownBy(() -> factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(process.isDestroyed()).isTrue();
        assertThat(listener.isCancelled()).isTrue();
    }

    @Test
    void should_release_process_when_error_stream_access_fails() throws Exception {
        Path home = createJdtLsHome();
        ErrorStreamFailureProcess process = new ErrorStreamFailureProcess();
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> {
                    throw new AssertionError("connection must not start");
                });

        assertThatThrownBy(() -> factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error stream access failed");

        assertThat(process.isDestroyed()).isTrue();
    }

    @Test
    void should_release_process_when_stderr_thread_start_fails() throws Exception {
        Path home = createJdtLsHome();
        TestProcess process = new TestProcess(new ByteArrayInputStream(new byte[0]));
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> {
                    throw new AssertionError("connection must not start");
                },
                task -> {
                    throw new AssertionError("stderr thread start failed");
                });

        assertThatThrownBy(() -> factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessage("stderr thread start failed");

        assertThat(process.isDestroyed()).isTrue();
    }

    @Test
    void should_continue_cleanup_and_preserve_failure_when_cleanup_actions_fail() throws Exception {
        Path home = createJdtLsHome();
        FaultyCleanupProcess process = new FaultyCleanupProcess();
        FailingCancelFuture listener = new FailingCancelFuture();
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.failedFuture(new IllegalStateException("startup failed"));
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(server, listener));

        try {
            ExecutionException failure = catchThrowableOfType(
                    ExecutionException.class,
                    () -> factory.launch(
                            tempDirectory.resolve("repository"),
                            tempDirectory.resolve("workspace-data"),
                            mock(JdtLanguageClient.class)));

            assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
            assertThat(failure.getSuppressed()).hasSizeGreaterThanOrEqualTo(4);
            assertThat(process.getOutputAccessCount()).isPositive();
            assertThat(process.getInputCloseCount()).isPositive();
            assertThat(process.getErrorCloseCount()).isPositive();
            assertThat(process.getDestroyCount()).isPositive();
            assertThat(process.getForcibleDestroyCount()).isPositive();
            assertThat(process.getTimedWaitCount()).isGreaterThanOrEqualTo(2);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void should_preserve_original_and_continue_cleanup_when_cleanup_rethrows_same_failure()
            throws Exception {
        Path home = createJdtLsHome();
        SelfSuppressionProcess process = new SelfSuppressionProcess();
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> {
                    throw new AssertionError("connection must not start");
                });

        Throwable failure = catchThrowable(() -> factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class)));

        assertThat(failure).isSameAs(process.getSharedFailure());
        assertThat(process.getDestroyCount()).isPositive();
        assertThat(process.getTimedWaitCount()).isPositive();
    }

    @Test
    void should_expose_sanitized_failure_and_release_resources_when_stderr_drain_fails(
            CapturedOutput output) throws Exception {
        Path home = createJdtLsHome();
        ControlledFailureInputStream stderr = new ControlledFailureInputStream();
        TestProcess process = new TestProcess(stderr);
        CompletableFuture<Void> listener = new CompletableFuture<>();
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.completedFuture(null);
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(server, listener));
        JdtLsProcessFactory.LaunchHandle handle = factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class));
        assertThat(stderr.awaitRead()).isTrue();

        stderr.fail();
        ExecutionException failure = catchThrowableOfType(
                ExecutionException.class,
                () -> handle.stderrDrain().get(2, TimeUnit.SECONDS));

        assertThat(failure.getCause()).isInstanceOf(JdtLsProcessFactory.StderrDrainException.class);
        assertThat(failure.getCause().getMessage()).doesNotContain("https://user:secret@example.test");
        assertThat(output.getAll()).doesNotContain("https://user:secret@example.test");
        assertThat(listener.isCancelled()).isTrue();
        assertThat(process.isDestroyed()).isTrue();
    }

    @Test
    void should_publish_sanitized_failure_after_teardown_when_stderr_close_throws(
            CapturedOutput output) throws Exception {
        ControlledCloseFailureInputStream stderr = new ControlledCloseFailureInputStream();
        DrainFailureFixture fixture = launchDrainFailureFixture(stderr);
        assertThat(stderr.awaitRead()).isTrue();

        stderr.release();
        Throwable observed = catchThrowable(
                () -> fixture.handle().stderrDrain().get(2, TimeUnit.SECONDS));

        assertSanitizedDrainFailure(observed, output, ControlledCloseFailureInputStream.SENSITIVE_VALUE);
        assertThat(stderr.getCloseCount()).isPositive();
        assertThat(fixture.listener().isCancelled()).isTrue();
        assertThat(fixture.process().isDestroyed()).isTrue();
    }

    @Test
    void should_publish_sanitized_failure_after_teardown_when_stderr_read_throws_runtime_exception(
            CapturedOutput output) throws Exception {
        ControlledRuntimeFailureInputStream stderr = new ControlledRuntimeFailureInputStream();
        DrainFailureFixture fixture = launchDrainFailureFixture(stderr);
        assertThat(stderr.awaitRead()).isTrue();

        stderr.release();
        Throwable observed = catchThrowable(
                () -> fixture.handle().stderrDrain().get(2, TimeUnit.SECONDS));

        assertSanitizedDrainFailure(observed, output, ControlledRuntimeFailureInputStream.SENSITIVE_VALUE);
        assertThat(fixture.listener().isCancelled()).isTrue();
        assertThat(fixture.process().isDestroyed()).isTrue();
    }

    @Test
    void should_publish_sanitized_failure_after_teardown_when_stderr_read_throws_error(
            CapturedOutput output) throws Exception {
        ControlledErrorFailureInputStream stderr = new ControlledErrorFailureInputStream();
        DrainFailureFixture fixture = launchDrainFailureFixture(stderr);
        assertThat(stderr.awaitRead()).isTrue();

        stderr.release();
        Throwable observed = catchThrowable(
                () -> fixture.handle().stderrDrain().get(2, TimeUnit.SECONDS));

        assertSanitizedDrainFailure(observed, output, ControlledErrorFailureInputStream.SENSITIVE_VALUE);
        assertThat(fixture.listener().isCancelled()).isTrue();
        assertThat(fixture.process().isDestroyed()).isTrue();
    }

    @Test
    void should_drain_stderr_independently_when_pipe_capacity_is_exceeded() throws Exception {
        Path home = createJdtLsHome();
        PipedInputStream stderr = new PipedInputStream(128);
        PipedOutputStream serverStderr = new PipedOutputStream(stderr);
        TestProcess process = new TestProcess(stderr);
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.completedFuture(null);
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(
                        server, CompletableFuture.completedFuture(null)));
        JdtLsProcessFactory.LaunchHandle handle = factory.launch(
                tempDirectory.resolve("repository"), tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class));
        CountDownLatch writeCompleted = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().daemon(true).start(() -> {
            try (OutputStream output = serverStderr) {
                output.write(new byte[65_536]);
                writeCompleted.countDown();
            } catch (IOException exception) {
                throw new IllegalStateException("fake stderr write failed", exception);
            }
        });

        try {
            assertThat(writeCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            writer.join(Duration.ofSeconds(2));
            assertThat(handle.stderrDrain().get(2, TimeUnit.SECONDS)).isNull();
            assertThat(writer.isAlive()).isFalse();
        } finally {
            serverStderr.close();
            stderr.close();
            writer.join(Duration.ofSeconds(2));
        }
    }

    @Test
    void should_retain_stderr_lines_in_the_session_buffer_when_the_server_writes_diagnostics()
            throws Exception {
        Path home = createJdtLsHome();
        String diagnostics = "!ENTRY org.eclipse.jdt.ls.core\n!MESSAGE import failed\n";
        TestProcess process = new TestProcess(
                new ByteArrayInputStream(diagnostics.getBytes(StandardCharsets.UTF_8)));
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.completedFuture(null);
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(
                        server, CompletableFuture.completedFuture(null)));

        JdtLsProcessFactory.LaunchHandle handle = factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class));

        assertThat(handle.stderrDrain().get(2, TimeUnit.SECONDS)).isNull();
        assertThat(handle.stderrBuffer().lines())
                .containsExactly("!ENTRY org.eclipse.jdt.ls.core", "!MESSAGE import failed");
    }

    @Test
    void should_drop_the_oldest_line_and_truncate_when_the_stderr_buffer_overflows() {
        StderrRingBuffer buffer = new StderrRingBuffer(2);

        buffer.add("first");
        buffer.add("second");
        buffer.add("third");
        buffer.add("x".repeat(StderrRingBuffer.MAX_LINE_LENGTH + 50));

        assertThat(buffer.lines()).hasSize(2);
        assertThat(buffer.lines().getFirst()).isEqualTo("third");
        assertThat(buffer.lines().getLast())
                .hasSize(StderrRingBuffer.MAX_LINE_LENGTH)
                .endsWith("...");
        assertThat(buffer.asText()).contains("third");
    }

    private Path createJdtLsHome() throws IOException {
        Path home = tempDirectory.resolve("jdtls");
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("config_linux"));
        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_test.jar"));
        return home;
    }

    private JdtLsProperties properties(Path home) {
        return new JdtLsProperties(
                true,
                home,
                tempDirectory.resolve("data"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                1,
                Duration.ofMinutes(1),
                "768m");
    }

    private DrainFailureFixture launchDrainFailureFixture(InputStream stderr) throws Exception {
        Path home = createJdtLsHome();
        TestProcess process = new TestProcess(stderr);
        CompletableFuture<Void> listener = new CompletableFuture<>();
        LanguageServer server = mock(LanguageServer.class, invocation -> {
            if ("initialize".equals(invocation.getMethod().getName())) {
                return CompletableFuture.completedFuture(null);
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        JdtLsProcessFactory factory = new JdtLsProcessFactory(
                properties(home),
                command -> process,
                (client, launchedProcess) -> new JdtLsProcessFactory.Connection(server, listener));
        JdtLsProcessFactory.LaunchHandle handle = factory.launch(
                tempDirectory.resolve("repository"),
                tempDirectory.resolve("workspace-data"),
                mock(JdtLanguageClient.class));
        return new DrainFailureFixture(process, listener, handle);
    }

    private void assertSanitizedDrainFailure(
            Throwable observed,
            CapturedOutput output,
            String sensitiveValue) {
        assertThat(observed).isInstanceOf(ExecutionException.class);
        assertThat(observed.getCause()).isInstanceOf(JdtLsProcessFactory.StderrDrainException.class);
        assertThat(observed.getCause().getCause()).isNull();
        assertThat(observed.getCause().getSuppressed()).hasSize(0);
        assertThat(observed.getCause().getMessage()).doesNotContain(sensitiveValue);
        assertThat(output.getAll()).doesNotContain(sensitiveValue);
    }

    private record DrainFailureFixture(
            TestProcess process,
            CompletableFuture<Void> listener,
            JdtLsProcessFactory.LaunchHandle handle) {
    }

    private static class TestProcess extends Process {

        private final InputStream stderr;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private boolean destroyed;

        private TestProcess(InputStream stderr) {
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
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
            destroyed = true;
        }

        protected boolean isDestroyed() {
            return destroyed;
        }
    }

    private static final class ErrorStreamFailureProcess extends TestProcess {

        private ErrorStreamFailureProcess() {
            super(new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public InputStream getErrorStream() {
            throw new IllegalStateException("error stream access failed");
        }
    }

    private static final class FailingCancelFuture extends CompletableFuture<Void> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw new IllegalStateException("listener cancel failed");
        }
    }

    private static final class FaultyCleanupProcess extends Process {

        private final TrackingInputStream input = new TrackingInputStream(true);
        private final TrackingInputStream error = new TrackingInputStream(false);
        private int outputAccessCount;
        private int destroyCount;
        private int forcibleDestroyCount;
        private int timedWaitCount;

        @Override
        public OutputStream getOutputStream() {
            outputAccessCount++;
            throw new IllegalStateException("output stream access failed");
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            timedWaitCount++;
            if (timedWaitCount == 1) {
                throw new InterruptedException("graceful wait failed");
            }
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount++;
            throw new IllegalStateException("destroy failed");
        }

        @Override
        public Process destroyForcibly() {
            forcibleDestroyCount++;
            return this;
        }

        private int getOutputAccessCount() {
            return outputAccessCount;
        }

        private int getInputCloseCount() {
            return input.getCloseCount();
        }

        private int getErrorCloseCount() {
            return error.getCloseCount();
        }

        private int getDestroyCount() {
            return destroyCount;
        }

        private int getForcibleDestroyCount() {
            return forcibleDestroyCount;
        }

        private int getTimedWaitCount() {
            return timedWaitCount;
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final boolean failOnClose;
        private int closeCount;

        private TrackingInputStream(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (failOnClose) {
                throw new IOException("stream close failed");
            }
        }

        private int getCloseCount() {
            return closeCount;
        }
    }

    private static final class ControlledFailureInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch failureReleased = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                failureReleased.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("stderr read interrupted", exception);
            }
            throw new IOException("https://user:secret@example.test/repository");
        }

        private boolean awaitRead() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private void fail() {
            failureReleased.countDown();
        }
    }

    private static final class SelfSuppressionProcess extends Process {

        private final IllegalStateException sharedFailure =
                new IllegalStateException("shared process access failure");
        private int destroyCount;
        private int timedWaitCount;

        @Override
        public OutputStream getOutputStream() {
            throw sharedFailure;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            throw sharedFailure;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCount++;
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }

        private IllegalStateException getSharedFailure() {
            return sharedFailure;
        }

        private int getDestroyCount() {
            return destroyCount;
        }

        private int getTimedWaitCount() {
            return timedWaitCount;
        }
    }

    private abstract static class ControlledReadInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        protected void awaitRelease() throws IOException {
            readStarted.countDown();
            try {
                released.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("controlled read interrupted", exception);
            }
        }

        protected final boolean awaitRead() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        protected final void release() {
            released.countDown();
        }
    }

    private static final class ControlledCloseFailureInputStream extends ControlledReadInputStream {

        private static final String SENSITIVE_VALUE = "https://close-token@example.test";
        private int closeCount;

        @Override
        public int read() throws IOException {
            awaitRelease();
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            throw new IOException(SENSITIVE_VALUE);
        }

        private int getCloseCount() {
            return closeCount;
        }
    }

    private static final class ControlledRuntimeFailureInputStream extends ControlledReadInputStream {

        private static final String SENSITIVE_VALUE = "runtime-token=secret-runtime";

        @Override
        public int read() throws IOException {
            awaitRelease();
            throw new IllegalStateException(SENSITIVE_VALUE);
        }
    }

    private static final class ControlledErrorFailureInputStream extends ControlledReadInputStream {

        private static final String SENSITIVE_VALUE = "error-token=secret-error";

        @Override
        public int read() throws IOException {
            awaitRelease();
            throw new AssertionError(SENSITIVE_VALUE);
        }
    }
}
