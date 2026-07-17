package com.java.semantic.repository.application;

import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
                        await(readerMayFinish);
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
    void should_log_mutation_context_and_cause_without_repository_url_or_credentials(
            MutationOperation operation, CapturedOutput output) {
        FakeGitRepositoryPort git = new FakeGitRepositoryPort();
        DefaultRepositoryApplicationService service = service(git, Duration.ofMillis(100));
        prepareFailedMutation(service, git, operation);
        int outputStart = output.getAll().length();

        assertThatThrownBy(() -> executeMutation(service, operation))
                .isInstanceOf(RepositoryMutationException.class);

        String mutationOutput = output.getAll().substring(outputStart);
        assertThat(mutationOutput).contains("repositoryId=test-repo");
        assertThat(mutationOutput).contains("operation=" + operation.logName());
        assertThat(mutationOutput).contains("RepositoryMutationException: repository mutation failed");
        assertThat(mutationOutput).contains("cause type=IllegalStateException");
        assertThat(mutationOutput).doesNotContain("https://");
        assertThat(mutationOutput).doesNotContain("ghp_realsecretvalue");
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
                    await(releaseLock);
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
                registry, new FakeGitRepositoryPort(), List.of(), properties);

        RepositoryStatus status = service.ensure(REPOSITORY_ID);

        assertThat(status.currentRevision()).contains(RepositoryRevision.fixture());
        assertThat(status.currentBranch()).isEmpty();
        assertThatThrownBy(() -> service.sync(REPOSITORY_ID, Optional.empty()))
                .isInstanceOf(ImmutableFixtureException.class);
        assertThatThrownBy(() -> service.checkout(REPOSITORY_ID, "main"))
                .isInstanceOf(ImmutableFixtureException.class);
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
            List<RepositoryMutationListener> listeners,
            Duration lockTimeout) {
        RepositoryProperties properties = properties(lockTimeout);
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        return new DefaultRepositoryApplicationService(registry, git, listeners, properties);
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch interrupted", exception);
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
            await(mutationMayFinish);
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
                    "planned mutation failure: https://user:ghp_realsecretvalue@example.com/repo.git",
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
