package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultJdtWorkspaceManagerTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("order-service");
    private static final RepositoryRevision REVISION =
            RepositoryRevision.ofSha("a".repeat(40));
    private static final String SANITY_TYPE = "OrderCrudService";

    @TempDir
    Path tempDirectory;

    @Test
    void should_reach_ready_when_import_settles_and_the_symbol_query_returns_symbols() {
        Fixture fixture = new Fixture();

        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());

        assertThat(session.status()).isEqualTo(SemanticEngineStatus.READY);
        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.READY);
        assertThat(session.revision()).isEqualTo(REVISION);
        assertThat(fixture.workspaceService().queries()).containsExactly(SANITY_TYPE);
    }

    @Test
    void should_keep_polling_the_symbol_query_when_service_ready_fires_before_symbols_resolve() {
        Fixture fixture = new Fixture();
        AtomicInteger attempts = new AtomicInteger();
        List<SemanticEngineStatus> observed = Collections.synchronizedList(new ArrayList<>());
        fixture.workspaceService().respondWith(query -> {
            observed.add(fixture.manager().status(REPOSITORY_ID));
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.completedFuture(Either.forRight(List.of()));
            }
            return CompletableFuture.completedFuture(symbols());
        });

        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(observed).containsExactly(
                SemanticEngineStatus.IMPORTING,
                SemanticEngineStatus.IMPORTING,
                SemanticEngineStatus.IMPORTING);
        assertThat(session.status()).isEqualTo(SemanticEngineStatus.READY);
    }

    @Test
    void should_not_reach_ready_when_progress_is_still_active_although_symbols_resolve() {
        Fixture fixture = new Fixture();
        fixture.client(client -> client.notifyProgress(progress("import", new WorkDoneProgressBegin())));

        assertThatThrownBy(() -> fixture.manager().getOrStart(fixture.snapshot()))
                .isInstanceOf(JdtLsReadinessProbe.JdtWorkspaceStartupException.class);

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.FAILED);
    }

    @Test
    void should_reach_ready_when_progress_ends_after_it_began() {
        Fixture fixture = new Fixture();
        fixture.client(client -> {
            client.notifyProgress(progress("import", new WorkDoneProgressBegin()));
            client.notifyProgress(progress("import", new WorkDoneProgressEnd()));
        });

        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());

        assertThat(session.status()).isEqualTo(SemanticEngineStatus.READY);
    }

    @Test
    void should_report_stderr_diagnostics_when_the_import_times_out() {
        Fixture fixture = new Fixture();
        fixture.stderr("!ENTRY org.eclipse.jdt.ls.core 4\n!MESSAGE could not resolve pom.xml\n");
        fixture.workspaceService().respondWith(
                query -> CompletableFuture.completedFuture(Either.forRight(List.of())));

        JdtLsReadinessProbe.JdtWorkspaceStartupException failure = catchThrowableOfType(
                JdtLsReadinessProbe.JdtWorkspaceStartupException.class,
                () -> fixture.manager().getOrStart(fixture.snapshot()));

        assertThat(failure).hasMessageContaining("order-service");
        assertThat(failure.stderr()).contains("!MESSAGE could not resolve pom.xml");
        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.FAILED);
        assertThat(fixture.languageServer().lifecycleCalls()).containsExactly("shutdown", "exit");
    }

    @Test
    void should_keep_the_startup_diagnostics_when_stopping_the_failed_session_also_fails() {
        Fixture fixture = new Fixture();
        fixture.stderr("!MESSAGE could not resolve pom.xml\n");
        fixture.workspaceService().respondWith(
                query -> CompletableFuture.completedFuture(Either.forRight(List.of())));
        fixture.failStopWith(new IllegalStateException("destroy exploded"));

        JdtLsReadinessProbe.JdtWorkspaceStartupException failure = catchThrowableOfType(
                JdtLsReadinessProbe.JdtWorkspaceStartupException.class,
                () -> fixture.manager().getOrStart(fixture.snapshot()));

        assertThat(failure.stderr()).contains("!MESSAGE could not resolve pom.xml");
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.FAILED);
    }

    @Test
    void should_abort_startup_when_the_repository_is_mutated_while_importing() {
        Fixture fixture = new Fixture();
        fixture.workspaceService().respondWith(query -> {
            fixture.manager().invalidate(REPOSITORY_ID);
            return CompletableFuture.completedFuture(Either.forRight(List.of()));
        });

        assertThatThrownBy(() -> fixture.manager().getOrStart(fixture.snapshot()))
                .isInstanceOf(JdtLsReadinessProbe.JdtWorkspaceStartupException.class)
                .hasMessageContaining("invalidated");
    }

    @Test
    void should_abort_startup_when_the_process_dies_while_importing() {
        Fixture fixture = new Fixture();
        fixture.workspaceService().respondWith(query -> {
            fixture.process().kill();
            return CompletableFuture.completedFuture(Either.forRight(List.of()));
        });

        assertThatThrownBy(() -> fixture.manager().getOrStart(fixture.snapshot()))
                .isInstanceOf(JdtLsReadinessProbe.JdtWorkspaceStartupException.class)
                .hasMessageContaining("exited");
    }

    @Test
    void should_start_one_process_when_the_same_repository_is_requested_twice() {
        Fixture fixture = new Fixture();

        JdtWorkspaceSession first = fixture.manager().getOrStart(fixture.snapshot());
        JdtWorkspaceSession second = fixture.manager().getOrStart(fixture.snapshot());

        assertThat(second).isSameAs(first);
        assertThat(fixture.startedCommands()).hasSize(1);
    }

    @Test
    void should_start_one_process_when_two_threads_request_the_same_repository() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<JdtWorkspaceSession> sessions = Collections.synchronizedList(new ArrayList<>());
        Runnable start = () -> {
            ready.countDown();
            awaitLatch(go);
            sessions.add(fixture.manager().getOrStart(fixture.snapshot()));
        };
        Thread first = Thread.ofPlatform().start(start);
        Thread second = Thread.ofPlatform().start(start);

        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        first.join(Duration.ofSeconds(5));
        second.join(Duration.ofSeconds(5));

        assertThat(fixture.startedCommands()).hasSize(1);
        assertThat(sessions).hasSize(2);
        assertThat(sessions.getFirst()).isSameAs(sessions.getLast());
    }

    @Test
    void should_isolate_workspace_data_per_repository_when_starting() {
        Fixture fixture = new Fixture();

        fixture.manager().getOrStart(fixture.snapshot());

        List<String> command = fixture.startedCommands().getFirst();
        assertThat(command).containsSequence(
                "-data", tempDirectory.resolve("jdtls-data").resolve("order-service").toString());
    }

    @Test
    void should_stop_the_session_when_the_repository_is_mutated() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());

        fixture.manager().beforeMutation(REPOSITORY_ID);

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
        assertThat(fixture.languageServer().lifecycleCalls()).containsExactly("shutdown", "exit");
    }

    @Test
    void should_wrap_the_failure_in_a_repository_mutation_exception_when_invalidation_fails() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());
        fixture.process().refuseToExit();
        fixture.process().failDestroyForciblyWith(new IllegalStateException("destroy exploded"));

        assertThatThrownBy(() -> fixture.manager().beforeMutation(REPOSITORY_ID))
                .isInstanceOf(RepositoryMutationException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_still_stop_the_session_when_the_shutdown_request_fails() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());
        fixture.languageServer().failShutdownWith(new IllegalStateException("shutdown exploded"));

        fixture.manager().beforeMutation(REPOSITORY_ID);

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
        assertThat(fixture.languageServer().lifecycleCalls()).containsExactly("shutdown", "exit");
    }

    @Test
    void should_ignore_mutation_when_the_repository_has_no_session() {
        Fixture fixture = new Fixture();

        fixture.manager().beforeMutation(REPOSITORY_ID);

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
    }

    @Test
    void should_evict_the_idle_session_when_capacity_is_reached() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());
        RepositorySnapshot other = fixture.snapshotFor(RepositoryId.of("billing-service"));

        JdtWorkspaceSession evicting = fixture.manager().getOrStart(other);

        assertThat(evicting.status()).isEqualTo(SemanticEngineStatus.READY);
        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
        assertThat(fixture.manager().status(RepositoryId.of("billing-service")))
                .isEqualTo(SemanticEngineStatus.READY);
    }

    @Test
    void should_refuse_to_evict_when_every_session_has_an_active_request() throws Exception {
        Fixture fixture = new Fixture(Duration.ofSeconds(30));
        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());
        CountDownLatch inFlight = new CountDownLatch(1);
        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
                pending = new CompletableFuture<>();
        fixture.workspaceService().respondWith(query -> {
            inFlight.countDown();
            return pending;
        });
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                session.call("workspace/symbol",
                        server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams("x")));
            } catch (RuntimeException ignored) {
                // the request is cancelled by the timeout once the assertion below has run
            }
        });
        assertThat(inFlight.await(2, TimeUnit.SECONDS)).isTrue();
        RepositorySnapshot other = fixture.snapshotFor(RepositoryId.of("billing-service"));

        try {
            assertThat(session.activeRequests()).isEqualTo(1);
            assertThatThrownBy(() -> fixture.manager().getOrStart(other))
                    .isInstanceOf(DefaultJdtWorkspaceManager.JdtWorkspaceCapacityException.class);
            assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.READY);
        } finally {
            pending.cancel(true);
            caller.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void should_reject_a_late_request_when_the_session_was_evicted_out_from_under_the_caller() {
        Fixture fixture = new Fixture();
        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());
        RepositorySnapshot other = fixture.snapshotFor(RepositoryId.of("billing-service"));

        fixture.manager().getOrStart(other);

        assertThatThrownBy(() -> session.call("workspace/symbol",
                server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams("x"))))
                .isInstanceOf(JdtWorkspaceSession.JdtRequestFailedException.class);
        assertThat(session.activeRequests()).isZero();
    }

    @Test
    void should_force_destroy_the_process_when_it_does_not_exit_within_the_bounded_wait() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());
        fixture.process().refuseToExit();

        fixture.manager().shutdownAll();

        assertThat(fixture.languageServer().lifecycleCalls()).containsExactly("shutdown", "exit");
        assertThat(fixture.process().isDestroyedForcibly()).isTrue();
    }

    @Test
    void should_stop_every_session_when_shutting_down() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());

        fixture.manager().shutdownAll();

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
        assertThat(fixture.languageServer().lifecycleCalls()).contains("shutdown", "exit");
    }

    @Test
    void should_return_promptly_from_shutdown_when_a_start_holds_the_lifecycle_lock() throws Exception {
        Fixture fixture = new Fixture(Duration.ofMillis(200), Duration.ofMillis(100));
        CountDownLatch importReached = new CountDownLatch(1);
        CountDownLatch releaseImport = new CountDownLatch(1);
        fixture.workspaceService().respondWith(query -> {
            importReached.countDown();
            awaitLatch(releaseImport);
            return CompletableFuture.completedFuture(symbols());
        });
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                fixture.manager().getOrStart(fixture.snapshot());
            } catch (RuntimeException ignored) {
                // the concurrent shutdown may tear the still-initialising session down
            }
        });

        try {
            assertThat(importReached.await(2, TimeUnit.SECONDS)).isTrue();

            fixture.manager().shutdownAll();

            assertThat(fixture.languageServer().lifecycleCalls()).contains("shutdown", "exit");
            assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
        } finally {
            releaseImport.countDown();
            starter.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void should_not_start_a_process_when_get_or_start_runs_after_shutdown() {
        Fixture fixture = new Fixture();
        fixture.manager().shutdownAll();

        catchThrowable(() -> fixture.manager().getOrStart(fixture.snapshot()));

        assertThat(fixture.startedCommands()).isEmpty();
    }

    @Test
    void should_reject_get_or_start_with_a_clear_error_after_shutdown() {
        Fixture fixture = new Fixture();
        fixture.manager().shutdownAll();

        assertThatThrownBy(() -> fixture.manager().getOrStart(fixture.snapshot()))
                .isInstanceOf(DefaultJdtWorkspaceManager.JdtWorkspaceManagerStoppedException.class);
    }

    @Test
    void should_report_stopped_when_the_repository_was_never_started() {
        Fixture fixture = new Fixture();

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.STOPPED);
    }

    @Test
    void should_report_failed_when_the_process_cannot_be_launched() {
        Fixture fixture = new Fixture();
        fixture.failProcessStartWith(new IOException("no such executable"));

        assertThatThrownBy(() -> fixture.manager().getOrStart(fixture.snapshot()))
                .isInstanceOf(JdtLsReadinessProbe.JdtWorkspaceStartupException.class)
                .hasRootCauseInstanceOf(IOException.class);

        assertThat(fixture.manager().status(REPOSITORY_ID)).isEqualTo(SemanticEngineStatus.FAILED);
    }

    @Test
    void should_fail_the_request_with_a_typed_timeout_when_the_server_never_answers() {
        Fixture fixture = new Fixture();
        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());
        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
                pending = new CompletableFuture<>();
        fixture.workspaceService().respondWith(query -> pending);

        assertThatThrownBy(() -> session.call("workspace/symbol",
                server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams("x"))))
                .isInstanceOf(JdtWorkspaceSession.JdtRequestTimeoutException.class)
                .hasMessageContaining("workspace/symbol");

        assertThat(pending.isCancelled()).isTrue();
        assertThat(session.activeRequests()).isZero();
    }

    @Test
    void should_fail_the_request_when_the_server_answers_with_an_error() {
        Fixture fixture = new Fixture();
        JdtWorkspaceSession session = fixture.manager().getOrStart(fixture.snapshot());
        fixture.workspaceService().respondWith(query -> CompletableFuture.failedFuture(
                new IllegalStateException("server crashed")));

        assertThatThrownBy(() -> session.call("workspace/symbol",
                server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams("x"))))
                .isInstanceOf(JdtWorkspaceSession.JdtRequestFailedException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(session.activeRequests()).isZero();
    }

    @Test
    void should_publish_the_peak_rss_gauge_when_a_session_is_running() {
        assumeTrue(Files.isDirectory(Path.of("/proc")), "peak RSS is read from /proc");
        Fixture fixture = new Fixture();
        fixture.reportPid(ProcessHandle.current().pid());

        fixture.manager().getOrStart(fixture.snapshot());

        Double peak = fixture.meterRegistry()
                .get("jdtls.workspace.peak.rss.kilobytes")
                .tag("repository", "order-service")
                .gauge()
                .value();
        assertThat(peak).isGreaterThan(0.0);
    }

    @Test
    void should_remove_the_peak_rss_gauge_when_the_session_stops() {
        Fixture fixture = new Fixture();
        fixture.manager().getOrStart(fixture.snapshot());

        fixture.manager().shutdownAll();

        assertThat(fixture.meterRegistry().find("jdtls.workspace.peak.rss.kilobytes").gauge()).isNull();
    }

    private static Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> symbols() {
        return Either.forRight(List.of(new WorkspaceSymbol()));
    }

    private static ProgressParams progress(String token, WorkDoneProgressNotification value) {
        ProgressParams params = new ProgressParams();
        params.setToken(token);
        params.setValue(Either.forLeft(value));
        return params;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch interrupted", exception);
        }
    }

    /** 以假造程序與假造語言伺服器組出工作區管理器,不啟動任何真實 JDT LS */
    private final class Fixture {

        private final List<List<String>> startedCommands = Collections.synchronizedList(new ArrayList<>());
        private final FakeLanguageServer languageServer = new FakeLanguageServer();
        private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final AtomicReference<FakeProcess> process = new AtomicReference<>();
        private final DefaultJdtWorkspaceManager manager;

        private volatile String stderr = "";
        private volatile IOException processStartFailure;
        private volatile RuntimeException stopFailure;
        private volatile long pid = -1;
        private volatile Consumer<JdtLsReadinessProbe.ImportProgressClient> clientDriver =
                client -> client.languageStatus(new StatusReport("ServiceReady", ""));

        private Fixture() {
            this(Duration.ofMillis(200));
        }

        private Fixture(Duration requestTimeout) {
            this(requestTimeout, Duration.ofSeconds(5));
        }

        private Fixture(Duration requestTimeout, Duration shutdownLockWait) {
            Path home = createHome();
            JdtLsProperties properties = new JdtLsProperties(
                    true,
                    home,
                    tempDirectory.resolve("jdtls-data"),
                    Duration.ofSeconds(2),
                    Duration.ofMillis(300),
                    requestTimeout,
                    1,
                    Duration.ofMinutes(30),
                    "2g");
            JdtLsProcessFactory factory = new JdtLsProcessFactory(
                    properties,
                    command -> {
                        if (Objects.nonNull(processStartFailure)) {
                            throw processStartFailure;
                        }
                        startedCommands.add(List.copyOf(command));
                        FakeProcess started = new FakeProcess(
                                new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)));
                        started.reportPid(pid);
                        if (Objects.nonNull(stopFailure)) {
                            started.refuseToExit();
                            started.failDestroyForciblyWith(stopFailure);
                        }
                        process.set(started);
                        return started;
                    },
                    (client, launchedProcess) -> {
                        clientDriver.accept((JdtLsReadinessProbe.ImportProgressClient) client);
                        return new JdtLsProcessFactory.Connection(
                                languageServer, new CompletableFuture<>());
                    });
            this.manager = new DefaultJdtWorkspaceManager(
                    factory,
                    new JdtLsReadinessProbe(properties.getImportTimeout(), Duration.ofMillis(5)),
                    properties,
                    meterRegistry,
                    shutdownLockWait);
        }

        private Path createHome() {
            try {
                Path home = tempDirectory.resolve("jdtls");
                Files.createDirectories(home.resolve("plugins"));
                Files.createDirectories(home.resolve("config_linux"));
                Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_test.jar"));
                return home;
            } catch (IOException exception) {
                throw new IllegalStateException("fake JDT LS home failed", exception);
            }
        }

        private RepositorySnapshot snapshot() {
            return snapshotFor(REPOSITORY_ID);
        }

        private RepositorySnapshot snapshotFor(RepositoryId repositoryId) {
            try {
                Path root = tempDirectory.resolve("repos").resolve(repositoryId.value());
                Path source = root.resolve("src/main/java/com/example");
                Files.createDirectories(source);
                Files.writeString(source.resolve(SANITY_TYPE + ".java"),
                        "package com.example; public class " + SANITY_TYPE + " {}");
                return new RepositorySnapshot(repositoryId, root, REVISION);
            } catch (IOException exception) {
                throw new IllegalStateException("fake repository failed", exception);
            }
        }

        private DefaultJdtWorkspaceManager manager() {
            return manager;
        }

        private MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        private FakeLanguageServer languageServer() {
            return languageServer;
        }

        private FakeWorkspaceService workspaceService() {
            return languageServer.workspaceService();
        }

        private FakeProcess process() {
            return process.get();
        }

        private List<List<String>> startedCommands() {
            return List.copyOf(startedCommands);
        }

        private void stderr(String content) {
            this.stderr = content;
        }

        private void failProcessStartWith(IOException failure) {
            this.processStartFailure = failure;
        }

        private void reportPid(long value) {
            this.pid = value;
        }

        /** 讓停止流程在強制終結時失敗;程序拒絕結束才會走到那一步 */
        private void failStopWith(RuntimeException failure) {
            this.stopFailure = failure;
        }

        private void client(Consumer<JdtLsReadinessProbe.ImportProgressClient> driver) {
            this.clientDriver = client -> {
                client.languageStatus(new StatusReport("ServiceReady", ""));
                driver.accept(client);
            };
        }
    }

    private static final class FakeLanguageServer implements LanguageServer {

        private final List<String> lifecycleCalls = Collections.synchronizedList(new ArrayList<>());
        private final FakeWorkspaceService workspaceService = new FakeWorkspaceService();
        private volatile Supplier<CompletableFuture<Object>> shutdownResponse =
                () -> CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult());
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            lifecycleCalls.add("shutdown");
            return shutdownResponse.get();
        }

        @Override
        public void exit() {
            lifecycleCalls.add("exit");
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return null;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return workspaceService;
        }

        private FakeWorkspaceService workspaceService() {
            return workspaceService;
        }

        private List<String> lifecycleCalls() {
            return List.copyOf(lifecycleCalls);
        }

        private void failShutdownWith(RuntimeException failure) {
            this.shutdownResponse = () -> CompletableFuture.failedFuture(failure);
        }
    }

    private static final class FakeWorkspaceService implements WorkspaceService {

        private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
        private volatile Function<String, CompletableFuture<Either<List<? extends SymbolInformation>,
                List<? extends WorkspaceSymbol>>>> responder =
                query -> CompletableFuture.completedFuture(symbols());

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
            // the fake server has no configuration
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            // the fake server watches no files
        }

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>,
                List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
            queries.add(params.getQuery());
            return responder.apply(params.getQuery());
        }

        private List<String> queries() {
            return List.copyOf(queries);
        }

        private void respondWith(Function<String, CompletableFuture<Either<List<? extends SymbolInformation>,
                List<? extends WorkspaceSymbol>>>> responder) {
            this.responder = responder;
        }
    }

    private static final class FakeProcess extends Process {

        private final InputStream stderr;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean alive = true;
        private volatile boolean exits = true;
        private volatile boolean destroyedForcibly;
        private volatile long pid = -1;
        private volatile RuntimeException destroyForciblyFailure;

        private FakeProcess(InputStream stderr) {
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
        public boolean waitFor(long timeout, TimeUnit unit) {
            return exits;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            if (pid < 0) {
                return super.pid();
            }
            return pid;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // 這個 fake 以 destroyForcibly 追蹤終結
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly = true;
            if (Objects.nonNull(destroyForciblyFailure)) {
                throw destroyForciblyFailure;
            }
            return this;
        }

        private boolean isDestroyedForcibly() {
            return destroyedForcibly;
        }

        private void kill() {
            alive = false;
        }

        private void refuseToExit() {
            exits = false;
        }

        private void reportPid(long value) {
            this.pid = value;
        }

        private void failDestroyForciblyWith(RuntimeException failure) {
            this.destroyForciblyFailure = failure;
        }
    }
}
