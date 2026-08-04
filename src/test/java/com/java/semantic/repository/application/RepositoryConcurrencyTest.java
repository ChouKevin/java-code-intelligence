package com.java.semantic.repository.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.java.semantic.repository.port.RepositorySnapshotPublicationListener;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticBindingUnresolvedException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.support.ConcurrencyTestSupport;
import com.java.semantic.trie.ApiRouteIndexNotReadyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RepositoryConcurrencyTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("test-repo");
    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");

    @TempDir
    private Path tempDirectory;

    @Test
    void should_publish_fixture_once_through_snapshot_lifecycle() {
        RecordingPublicationListener publication = new RecordingPublicationListener();
        DefaultRepositoryApplicationService service = fixtureService(publication);

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(publication.events()).containsExactly(
                "before:test-repo",
                "after:test-repo:FIXTURE");
        assertThat(status.currentRevision()).contains(RepositoryRevision.fixture());
    }

    @Test
    void should_reconcile_existing_clone_without_firing_git_mutation_listener() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        git.markCloned();
        List<String> events = new ArrayList<>();
        RepositoryMutationListener mutation = repositoryId -> events.add("mutation");
        RepositorySnapshotPublicationListener publication = new RepositorySnapshotPublicationListener() {
            @Override
            public void beforePublication(RepositoryId repositoryId) {
                events.add("before");
            }

            @Override
            public void afterPublication(RepositorySnapshot snapshot) {
                events.add("after:" + snapshot.revision().value());
            }
        };
        DefaultRepositoryApplicationService service = service(
                git, List.of(mutation), List.of(publication), Duration.ofMillis(100));

        service.ensure(REPOSITORY_ID);

        assertThat(events).containsExactly("before", "after:" + SHA_ONE.value());
    }

    @Test
    void should_clear_existing_publication_before_current_revision_reconciliation() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        git.markCloned();
        git.failNextCurrentRevisionLookup();
        RecordingPublicationListener publication = new RecordingPublicationListener();
        publication.seedPublished(REPOSITORY_ID);
        DefaultRepositoryApplicationService service = service(
                git, List.of(), List.of(publication), Duration.ofMillis(100));

        assertThatThrownBy(() -> service.ensure(REPOSITORY_ID))
                .isInstanceOf(RepositoryMutationException.class);

        assertThat(publication.events()).containsExactly("before:test-repo");
        assertThat(publication.isPublished(REPOSITORY_ID)).isFalse();
    }

    @Test
    void should_not_fire_after_publication_when_branch_lookup_fails() {
        RecordingPublicationListener publication = new RecordingPublicationListener();
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(
                git, List.of(), List.of(publication), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        publication.clearEvents();
        git.failNextCurrentBranchLookup();

        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(RepositoryMutationException.class);

        assertThat(publication.events()).containsExactly("before:test-repo");
        assertThat(service.withSnapshot(
                REPOSITORY_ID, Optional.of(SHA_TWO), RepositorySnapshot::revision))
                .isEqualTo(SHA_TWO);
    }

    @Test
    void should_publish_snapshot_but_return_safe_failure_when_after_publication_fails(
            CapturedOutput output) {
        RepositorySnapshotPublicationListener publication = new RepositorySnapshotPublicationListener() {
            @Override
            public void beforePublication(RepositoryId repositoryId) {
            }

            @Override
            public void afterPublication(RepositorySnapshot snapshot) {
                throw new IllegalStateException("SECRET_PUBLICATION_FAILURE");
            }
        };
        DefaultRepositoryApplicationService service = service(
                new FakeGitRepositoryPort(), List.of(), List.of(publication), Duration.ofMillis(100));
        int outputStart = output.getAll().length();

        assertThatThrownBy(() -> service.ensure(REPOSITORY_ID))
                .isExactlyInstanceOf(RepositoryMutationException.class)
                .hasNoCause();
        assertThat(service.withSnapshot(
                REPOSITORY_ID, Optional.of(SHA_ONE), RepositorySnapshot::revision))
                .isEqualTo(SHA_ONE);
        assertThat(output.getAll().substring(outputStart))
                .doesNotContain("SECRET_PUBLICATION_FAILURE");
    }

    @Test
    void should_make_a_mutation_wait_when_a_reader_holds_the_snapshot() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofSeconds(2));
        service.ensure(REPOSITORY_ID);
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerMayFinish = new CountDownLatch(1);
        CountDownLatch mutationTaskStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RepositorySnapshot> reader = pool.submit(() -> service.withSnapshot(
                    REPOSITORY_ID, Optional.empty(), snapshot -> {
                        readerStarted.countDown();
                        ConcurrencyTestSupport.await(readerMayFinish, Duration.ofSeconds(5));
                        return snapshot;
                    }));
            assertThat(readerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RepositoryStatus> mutation = pool.submit(() -> {
                mutationTaskStarted.countDown();
                return service.sync(REPOSITORY_ID, Optional.empty());
            });
            assertThat(mutationTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> mutation.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            readerMayFinish.countDown();
            assertThat(reader.get(5, TimeUnit.SECONDS).revision()).isEqualTo(SHA_ONE);
            assertThat(mutation.get(5, TimeUnit.SECONDS).currentRevision())
                    .contains(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_make_a_reader_wait_when_a_mutation_holds_the_write_lock() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofSeconds(2));
        service.ensure(REPOSITORY_ID);
        git.blockNextMutation();
        CountDownLatch readerTaskStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RepositoryStatus> mutation = pool.submit(
                    () -> service.sync(REPOSITORY_ID, Optional.empty()));
            assertThat(git.awaitMutationStart()).isTrue();

            Future<RepositorySnapshot> reader = pool.submit(() -> {
                readerTaskStarted.countDown();
                return service.withSnapshot(
                        REPOSITORY_ID, Optional.empty(), snapshot -> snapshot);
            });
            assertThat(readerTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> reader.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            git.releaseMutation();
            assertThat(mutation.get(5, TimeUnit.SECONDS).currentRevision()).contains(SHA_TWO);
            assertThat(reader.get(5, TimeUnit.SECONDS).revision()).isEqualTo(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_release_the_read_lock_when_the_operation_throws() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);

        assertThatThrownBy(() -> service.withSnapshot(REPOSITORY_ID, Optional.empty(), snapshot -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RepositoryStatus> followUp = pool.submit(
                    () -> service.sync(REPOSITORY_ID, Optional.empty()));
            assertThat(followUp.get(5, TimeUnit.SECONDS).currentRevision()).contains(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_release_the_write_lock_when_the_mutation_throws() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        git.failNextMutation();

        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(RepositoryMutationException.class);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RepositoryStatus> followUp = pool.submit(
                    () -> service.sync(REPOSITORY_ID, Optional.empty()));
            assertThat(followUp.get(5, TimeUnit.SECONDS).currentRevision()).contains(SHA_TWO);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(MutationOperation.class)
    void should_log_only_safe_mutation_failure_event(MutationOperation operation) {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        prepareFailedMutation(service, git, operation);
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultRepositoryApplicationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> executeMutation(service, operation))
                    .isExactlyInstanceOf(RepositoryMutationException.class);

            assertThat(appender.list).singleElement().satisfies(event -> {
                String renderedOutput = event.getFormattedMessage();
                String arguments = Arrays.toString(event.getArgumentArray());
                assertThat(renderedOutput)
                        .contains("phase=repository-mutation", "outcome=failed", "repoId=test-repo",
                                "operation=" + operation.logName(),
                                "exceptionType=RepositoryMutationException")
                        .doesNotContain("MUTATION_FAILURE_SENTINEL", "https://",
                                "ghp_realsecretvalue", "IllegalStateException", "at ");
                assertThat(arguments)
                        .doesNotContain("MUTATION_FAILURE_SENTINEL", "https://",
                                "ghp_realsecretvalue", "IllegalStateException", "at ");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void should_log_expected_snapshot_failures_at_warn_without_a_throwable() {
        DefaultRepositoryApplicationService service = service(new FakeGitRepositoryPort(), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultRepositoryApplicationService.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            assertThatThrownBy(() -> service.withSnapshot(
                    REPOSITORY_ID,
                    Optional.empty(),
                    snapshot -> {
                        throw new RepositoryNotReadyException(REPOSITORY_ID);
                    }))
                    .isInstanceOf(RepositoryNotReadyException.class);

            assertSnapshotFailure(appender.list, Level.WARN, "RepositoryNotReadyException");
        } finally {
            detach(logger, appender);
        }
    }

    @Test
    void should_log_unexpected_snapshot_failures_at_error_without_a_throwable() {
        DefaultRepositoryApplicationService service = service(new FakeGitRepositoryPort(), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultRepositoryApplicationService.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            assertThatThrownBy(() -> service.withSnapshot(
                    REPOSITORY_ID,
                    Optional.empty(),
                    snapshot -> {
                        throw new IllegalStateException("RESTRICTED_SNAPSHOT_FAILURE_SENTINEL");
                    }))
                    .isInstanceOf(IllegalStateException.class);

            assertSnapshotFailure(appender.list, Level.ERROR, "IllegalStateException");
        } finally {
            detach(logger, appender);
        }
    }

    @Test
    void should_log_expected_callback_failures_at_warn() {
        DefaultRepositoryApplicationService service = service(new FakeGitRepositoryPort(), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderService"),
                        "OrderService.java"),
                "placeOrder",
                List.of());
        List<RuntimeException> expectedFailures = List.of(
                new SemanticBindingAmbiguousException(target, List.of(
                        target,
                        new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "AlternativeOrderService"),
                        "AlternativeOrderService.java"),
                "placeOrder",
                List.of()))),
                new SemanticBindingUnresolvedException(target),
                new SemanticRequestTimeoutException(),
                new SemanticTargetNotFoundException(target),
                new ApiRouteIndexNotReadyException(REPOSITORY_ID, SHA_ONE));
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultRepositoryApplicationService.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            for (RuntimeException expectedFailure : expectedFailures) {
                assertThatThrownBy(() -> service.withSnapshot(
                        REPOSITORY_ID,
                        Optional.empty(),
                        snapshot -> {
                            throw expectedFailure;
                        }))
                        .isSameAs(expectedFailure);
            }

            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("phase=snapshot outcome=failed"))
                    .toList();
            assertThat(failures).hasSize(5).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            detach(logger, appender);
        }
    }

    private void prepareFailedMutation(
            DefaultRepositoryApplicationService service,
            FakeGitRepositoryPort git,
            MutationOperation operation) {
        switch (operation) {
            case ENSURE -> git.failNextClone();
            case SYNC -> {
                service.ensure(REPOSITORY_ID);
                git.failNextMutation();
            }
            case CHECKOUT -> {
                service.ensure(REPOSITORY_ID);
                git.failNextCheckout();
            }
        }
    }

    private ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private void assertSnapshotFailure(List<ILoggingEvent> events, Level level, String exceptionType) {
        List<ILoggingEvent> failures = events.stream()
                .filter(event -> event.getFormattedMessage().contains("phase=snapshot outcome=failed"))
                .toList();
        assertThat(failures).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage())
                    .contains("repoId=test-repo", "exceptionType=" + exceptionType)
                    .doesNotContain("RESTRICTED_SNAPSHOT_FAILURE_SENTINEL", " at ");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private RepositoryStatus executeMutation(
            DefaultRepositoryApplicationService service, MutationOperation operation) {
        return switch (operation) {
            case ENSURE -> service.ensure(REPOSITORY_ID);
            case SYNC -> service.sync(REPOSITORY_ID, Optional.empty());
            case CHECKOUT -> service.checkout(REPOSITORY_ID, "feature");
        };
    }

    @Test
    void should_publish_mutated_revision_when_branch_lookup_fails_after_sync() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        git.failNextCurrentBranchLookup();

        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(RepositoryMutationException.class);

        RepositoryRevision revision = service.withSnapshot(
                REPOSITORY_ID, Optional.of(SHA_TWO), RepositorySnapshot::revision);
        assertThat(revision).isEqualTo(SHA_TWO);
        assertThatThrownBy(() -> service.withSnapshot(
                REPOSITORY_ID, Optional.of(SHA_ONE), RepositorySnapshot::revision))
                .isInstanceOf(RepositoryRevisionMismatchException.class);
    }

    @Test
    void should_return_a_conflict_when_expected_revision_does_not_match() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        RepositoryRevision stale = RepositoryRevision.ofSha("0".repeat(40));

        assertThatThrownBy(() -> service.withSnapshot(
                REPOSITORY_ID, Optional.of(stale), snapshot -> snapshot))
                .isInstanceOf(RepositoryRevisionMismatchException.class);
    }

    @Test
    void should_time_out_rather_than_block_forever_when_the_write_lock_is_held() throws Exception {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(50));
        service.ensure(REPOSITORY_ID);
        RepositoryRuntime runtime = service.registry().get(REPOSITORY_ID);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = pool.submit(() -> {
                Lock writeLock = runtime.lock().writeLock();
                boolean acquired = writeLock.tryLock(1, TimeUnit.SECONDS);
                if (!acquired) {
                    return false;
                }
                try {
                    lockHeld.countDown();
                    ConcurrencyTestSupport.await(releaseLock, Duration.ofSeconds(5));
                    return true;
                } finally {
                    writeLock.unlock();
                }
            });
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.status(REPOSITORY_ID))
                    .isInstanceOf(RepositoryBusyException.class);

            releaseLock.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void should_use_a_fair_lock_when_runtime_is_created() {
        DefaultRepositoryApplicationService service = service(
                new FakeGitRepositoryPort(), Duration.ofMillis(100));

        assertThat(service.registry().get(REPOSITORY_ID).lock().isFair()).isTrue();
    }

    @Test
    void should_report_fixture_and_reject_mutations_when_mode_is_local_fixture() {
        RepositoryProperties properties = properties(Duration.ofMillis(100));
        RepositoryProperties.RepositoryConfig config = properties.getRepositories().get("test-repo");
        config.setMode(RepositoryMode.LOCAL_FIXTURE);
        config.setPath(tempDirectory.toString());
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        DefaultRepositoryApplicationService service = new DefaultRepositoryApplicationService(
                registry, new FakeGitRepositoryPort(), List.of(), List.of(), properties);

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(status.currentRevision()).contains(RepositoryRevision.fixture());
        assertThat(status.currentBranch()).isEmpty();
        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(ImmutableFixtureException.class);
        assertThatThrownBy(() -> service.checkout(REPOSITORY_ID, "main"))
                .isInstanceOf(ImmutableFixtureException.class);
    }

    @Test
    void should_accept_fixture_as_expected_revision_for_fixture_snapshot() {
        RepositoryProperties properties = properties(Duration.ofMillis(100));
        RepositoryProperties.RepositoryConfig config = properties.getRepositories().get("test-repo");
        config.setMode(RepositoryMode.LOCAL_FIXTURE);
        config.setPath(tempDirectory.toString());
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        DefaultRepositoryApplicationService service = new DefaultRepositoryApplicationService(
                registry, new FakeGitRepositoryPort(), List.of(), List.of(), properties);
        service.ensure(REPOSITORY_ID);

        RepositoryRevision revision = service.withSnapshot(
                REPOSITORY_ID,
                Optional.of(RepositoryRevision.fixture()),
                RepositorySnapshot::revision);

        assertThat(revision).isEqualTo(RepositoryRevision.fixture());
    }

    @Test
    void should_notify_listener_before_clone_when_ensure_clones_repository() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(
                git, List.of(repositoryId -> git.recordListenerEvent()), Duration.ofMillis(100));

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(git.events()).containsExactly("listener", "clone");
        assertThat(status.currentRevision()).contains(SHA_ONE);
    }

    @Test
    void should_notify_listener_before_fetch_when_sync_mutates_repository() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(
                git, List.of(repositoryId -> git.recordListenerEvent()), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        git.clearEvents();

        RepositoryStatus status = service.sync(REPOSITORY_ID, Optional.empty());

        assertThat(git.events()).containsExactly("listener", "sync");
        assertThat(status.currentRevision()).contains(SHA_TWO);
    }

    @Test
    void should_notify_listener_before_checkout_when_checkout_mutates_repository() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(
                git, List.of(repositoryId -> git.recordListenerEvent()), Duration.ofMillis(100));
        service.ensure(REPOSITORY_ID);
        git.clearEvents();

        RepositoryStatus status = service.checkout(REPOSITORY_ID, "feature");

        assertThat(git.events()).containsExactly("listener", "checkout");
        assertThat(status.currentRevision()).contains(SHA_TWO);
    }

    @Test
    void should_prevent_git_mutation_when_listener_throws() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        RepositoryMutationListener listener = repositoryId -> {
            git.recordListenerEvent();
            throw new IllegalStateException("planned listener failure");
        };
        DefaultRepositoryApplicationService service = service(
                git, List.of(listener), Duration.ofMillis(100));

        assertThatThrownBy(() -> service.ensure(REPOSITORY_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(git.events()).containsExactly("listener");
        assertThat(git.isCloned(tempDirectory)).isFalse();
    }

    @Test
    void should_not_notify_listener_when_ensure_finds_existing_clone() {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        git.markCloned();
        DefaultRepositoryApplicationService service = service(
                git, List.of(repositoryId -> git.recordListenerEvent()), Duration.ofMillis(100));

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(git.events()).isEqualTo(List.of());
        assertThat(status.currentRevision()).contains(SHA_ONE);
    }

    private DefaultRepositoryApplicationService service(
            FakeGitRepositoryPort git, Duration lockTimeout) {
        return service(git, List.of(), lockTimeout);
    }

    private DefaultRepositoryApplicationService service(
            FakeGitRepositoryPort git,
            List<RepositoryMutationListener> mutationListeners,
            Duration lockTimeout) {
        return service(git, mutationListeners, List.of(), lockTimeout);
    }

    private DefaultRepositoryApplicationService service(
            FakeGitRepositoryPort git,
            List<RepositoryMutationListener> mutationListeners,
            List<RepositorySnapshotPublicationListener> publicationListeners,
            Duration lockTimeout) {
        RepositoryProperties properties = properties(lockTimeout);
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        return new DefaultRepositoryApplicationService(
                registry, git, mutationListeners, publicationListeners, properties);
    }

    private DefaultRepositoryApplicationService fixtureService(
            RepositorySnapshotPublicationListener publicationListener) {
        RepositoryProperties properties = properties(Duration.ofMillis(100));
        RepositoryProperties.RepositoryConfig config =
                properties.getRepositories().get(REPOSITORY_ID.value());
        config.setMode(RepositoryMode.LOCAL_FIXTURE);
        config.setPath(tempDirectory.toString());
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        return new DefaultRepositoryApplicationService(
                registry,
                new FakeGitRepositoryPort(),
                List.of(),
                List.of(publicationListener),
                properties);
    }

    private RepositoryProperties properties(Duration lockTimeout) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(tempDirectory.toString());
        properties.setRepositoryLockTimeout(lockTimeout);
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://user:ghp_realsecretvalue@example.com/repo.git");
        properties.getRepositories().put("test-repo", config);
        return properties;
    }

    private static final class RecordingPublicationListener
            implements RepositorySnapshotPublicationListener {

        private final List<String> events = new ArrayList<>();
        private final Set<RepositoryId> publishedRepositories = new HashSet<>();

        @Override
        public void beforePublication(RepositoryId repositoryId) {
            events.add("before:" + repositoryId.value());
            publishedRepositories.remove(repositoryId);
        }

        @Override
        public void afterPublication(RepositorySnapshot snapshot) {
            events.add("after:" + snapshot.repositoryId().value()
                    + ":" + snapshot.revision().value());
            publishedRepositories.add(snapshot.repositoryId());
        }

        void seedPublished(RepositoryId repositoryId) {
            publishedRepositories.add(repositoryId);
        }

        boolean isPublished(RepositoryId repositoryId) {
            return publishedRepositories.contains(repositoryId);
        }

        List<String> events() {
            return List.copyOf(events);
        }

        void clearEvents() {
            events.clear();
        }
    }

    private static final class FakeGitRepositoryPort implements GitRepositoryPort {

        private final List<String> events = new ArrayList<>();
        private boolean cloned;
        private RepositoryRevision revision = SHA_ONE;
        private CountDownLatch mutationStarted = new CountDownLatch(0);
        private CountDownLatch mutationMayFinish = new CountDownLatch(0);
        private boolean failClone;
        private boolean failMutation;
        private boolean failCheckout;
        private boolean failCurrentRevisionLookup;
        private boolean failCurrentBranchLookup;

        @Override
        public boolean isCloned(Path workingTree) {
            return cloned;
        }

        @Override
        public RepositoryRevision clone(Path workingTree, String url, String branch) {
            events.add("clone");
            if (failClone) {
                failClone = false;
                throw mutationFailure();
            }
            cloned = true;
            revision = SHA_ONE;
            return revision;
        }

        @Override
        public RepositoryRevision fetchAndReset(Path workingTree, String branch) {
            events.add("sync");
            mutationStarted.countDown();
            ConcurrencyTestSupport.await(mutationMayFinish, Duration.ofSeconds(5));
            if (failMutation) {
                failMutation = false;
                throw mutationFailure();
            }
            revision = SHA_TWO;
            return revision;
        }

        @Override
        public RepositoryRevision checkout(Path workingTree, String revisionValue) {
            events.add("checkout");
            if (failCheckout) {
                failCheckout = false;
                throw mutationFailure();
            }
            revision = SHA_TWO;
            return revision;
        }

        @Override
        public RepositoryRevision currentRevision(Path workingTree) {
            if (failCurrentRevisionLookup) {
                failCurrentRevisionLookup = false;
                throw new RepositoryMutationException("planned revision lookup failure");
            }
            return revision;
        }

        @Override
        public String currentBranch(Path workingTree) {
            if (failCurrentBranchLookup) {
                failCurrentBranchLookup = false;
                throw new RepositoryMutationException("planned branch lookup failure");
            }
            return "main";
        }

        void blockNextMutation() {
            mutationStarted = new CountDownLatch(1);
            mutationMayFinish = new CountDownLatch(1);
        }

        boolean awaitMutationStart() throws InterruptedException {
            return mutationStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseMutation() {
            mutationMayFinish.countDown();
        }

        void failNextMutation() {
            failMutation = true;
        }

        void failNextClone() {
            failClone = true;
        }

        void failNextCheckout() {
            failCheckout = true;
        }

        void failNextCurrentRevisionLookup() {
            failCurrentRevisionLookup = true;
        }

        void failNextCurrentBranchLookup() {
            failCurrentBranchLookup = true;
        }

        void recordListenerEvent() {
            events.add("listener");
        }

        List<String> events() {
            return List.copyOf(events);
        }

        void clearEvents() {
            events.clear();
        }

        void markCloned() {
            cloned = true;
            revision = SHA_ONE;
        }

        private RepositoryMutationException mutationFailure() {
            return new RepositoryMutationException(
                    "MUTATION_FAILURE_SENTINEL: https://user:ghp_realsecretvalue@example.com/repo.git",
                    new IllegalStateException("credential ghp_realsecretvalue was rejected"));
        }
    }

    private enum MutationOperation {
        ENSURE("ensure"),
        SYNC("sync"),
        CHECKOUT("checkout");

        private final String logName;

        MutationOperation(String logName) {
            this.logName = logName;
        }

        String logName() {
            return logName;
        }
    }
}
