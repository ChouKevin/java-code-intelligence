package com.java.semantic.repository.application;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.InvalidRepositoryIdException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.java.semantic.repository.port.RepositorySnapshotPublicationListener;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

/** 以每個儲存庫的公平讀寫鎖協調讀取與 Git 變更 */
@Service
@Slf4j
public class DefaultRepositoryApplicationService implements RepositoryApplicationService {

    private final RepositoryRuntimeRegistry registry;
    private final GitRepositoryPort gitRepositoryPort;
    private final List<RepositoryMutationListener> mutationListeners;
    private final List<RepositorySnapshotPublicationListener> publicationListeners;
    private final Duration lockTimeout;

    public DefaultRepositoryApplicationService(
            RepositoryRuntimeRegistry registry,
            GitRepositoryPort gitRepositoryPort,
            List<RepositoryMutationListener> mutationListeners,
            List<RepositorySnapshotPublicationListener> publicationListeners,
            RepositoryProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.gitRepositoryPort = Objects.requireNonNull(gitRepositoryPort, "gitRepositoryPort is required");
        this.mutationListeners = List.copyOf(mutationListeners);
        this.publicationListeners = List.copyOf(publicationListeners);
        this.lockTimeout = Objects.requireNonNull(
                properties.getRepositoryLockTimeout(), "repositoryLockTimeout is required");
    }

    @Override
    public RepositoryStatus ensure(RepositoryId repositoryId) {
        return withMutationLogging(repositoryId, "ensure", () -> {
            RepositoryRuntime runtime = registry.get(repositoryId);
            return withWriteLock(runtime, () -> ensureLocked(runtime));
        });
    }

    @Override
    public RepositoryStatus sync(RepositoryId repositoryId, Optional<String> branch) {
        return withMutationLogging(repositoryId, "sync", () -> {
            RepositoryRuntime runtime = registry.get(repositoryId);
            return withWriteLock(runtime, () -> {
                requireRemote(runtime);
                requireCloned(runtime);
                String targetBranch = branch.filter(StringUtils::hasText)
                        .orElse(runtime.defaultBranch());
                notifyBeforePublication(repositoryId);
                notifyBeforeMutation(repositoryId);
                RepositoryRevision revision = gitRepositoryPort.fetchAndReset(
                        runtime.workingTree(), targetBranch);
                return publishRevisionAndBranch(runtime, revision);
            });
        });
    }

    @Override
    public RepositoryStatus checkout(RepositoryId repositoryId, String revisionValue) {
        Assert.hasText(revisionValue, "revision is required");
        return withMutationLogging(repositoryId, "checkout", () -> {
            RepositoryRuntime runtime = registry.get(repositoryId);
            return withWriteLock(runtime, () -> {
                requireRemote(runtime);
                requireCloned(runtime);
                notifyBeforePublication(repositoryId);
                notifyBeforeMutation(repositoryId);
                RepositoryRevision revision = gitRepositoryPort.checkout(
                        runtime.workingTree(), revisionValue);
                return publishRevisionAndBranch(runtime, revision);
            });
        });
    }

    @Override
    public RepositoryStatus status(RepositoryId repositoryId) {
        RepositoryRuntime runtime = registry.get(repositoryId);
        return withReadLock(runtime, runtime::status);
    }

    @Override
    public List<RepositoryStatus> list() {
        return registry.all().stream()
                .map(runtime -> withReadLock(runtime, runtime::status))
                .toList();
    }

    @Override
    public <T> T withSnapshot(
            RepositoryId repositoryId,
            Optional<RepositoryRevision> expectedRevision,
            Function<RepositorySnapshot, T> operation) {
        RepositoryRuntime runtime = registry.get(repositoryId);
        return withReadLock(runtime, () -> {
            RepositorySnapshot snapshot = runtime.snapshot()
                    .orElseThrow(() -> new RepositoryNotReadyException(repositoryId));
            expectedRevision.ifPresent(expected -> requireRevision(snapshot, expected));
            long startedAt = System.nanoTime();
            try {
                T result = operation.apply(snapshot);
                log.info("phase=snapshot outcome=completed repoId={} revision={} durationMs={}",
                        repositoryId.value(), snapshot.revision().value(), elapsedMillis(startedAt));
                return result;
            } catch (RuntimeException exception) {
                if (isExpectedSnapshotFailure(exception)) {
                    log.warn("phase=snapshot outcome=failed repoId={} revision={} exceptionType={} durationMs={}",
                            repositoryId.value(), snapshot.revision().value(),
                            exception.getClass().getSimpleName(), elapsedMillis(startedAt));
                } else {
                    log.error("phase=snapshot outcome=failed repoId={} revision={} exceptionType={} durationMs={}",
                            repositoryId.value(), snapshot.revision().value(),
                            exception.getClass().getSimpleName(), elapsedMillis(startedAt));
                }
                throw exception;
            }
        });
    }

    RepositoryRuntimeRegistry registry() {
        return registry;
    }

    private RepositoryStatus ensureLocked(RepositoryRuntime runtime) {
        notifyBeforePublication(runtime.repositoryId());
        if (RepositoryMode.LOCAL_FIXTURE == runtime.mode()) {
            if (!Files.isDirectory(runtime.workingTree())
                    || !Files.isReadable(runtime.workingTree())) {
                throw new RepositoryMutationException("fixture path is unavailable");
            }
            RepositoryRevision revision = RepositoryRevision.fixture();
            runtime.publish(revision, "");
            notifyAfterPublication(new RepositorySnapshot(
                    runtime.repositoryId(), runtime.workingTree(), revision));
            return runtime.status();
        }
        RepositoryRevision revision;
        if (gitRepositoryPort.isCloned(runtime.workingTree())) {
            revision = gitRepositoryPort.currentRevision(runtime.workingTree());
        } else {
            if (!StringUtils.hasText(runtime.remoteUrl())) {
                throw new RepositoryMutationException("repository URL is not configured");
            }
            notifyBeforeMutation(runtime.repositoryId());
            revision = gitRepositoryPort.clone(
                    runtime.workingTree(), runtime.remoteUrl(), runtime.defaultBranch());
        }
        return publishRevisionAndBranch(runtime, revision);
    }

    private RepositoryStatus publishRevisionAndBranch(
            RepositoryRuntime runtime,
            RepositoryRevision revision) {
        runtime.publish(revision, "");
        String branch = gitRepositoryPort.currentBranch(runtime.workingTree());
        runtime.publish(revision, branch);
        notifyAfterPublication(new RepositorySnapshot(
                runtime.repositoryId(), runtime.workingTree(), revision));
        return runtime.status();
    }

    private void requireRemote(RepositoryRuntime runtime) {
        if (RepositoryMode.LOCAL_FIXTURE == runtime.mode()) {
            throw new ImmutableFixtureException();
        }
    }

    private void requireCloned(RepositoryRuntime runtime) {
        if (!gitRepositoryPort.isCloned(runtime.workingTree())) {
            throw new RepositoryNotReadyException(runtime.repositoryId());
        }
    }

    private void requireRevision(RepositorySnapshot snapshot, RepositoryRevision expected) {
        if (!snapshot.revision().equals(expected)) {
            throw new RepositoryRevisionMismatchException(expected, snapshot.revision());
        }
    }

    private void notifyBeforePublication(RepositoryId repositoryId) {
        try {
            for (RepositorySnapshotPublicationListener listener : publicationListeners) {
                listener.beforePublication(repositoryId);
            }
        } catch (RuntimeException exception) {
            throw new RepositoryMutationException("repository publication failed");
        }
    }

    private void notifyBeforeMutation(RepositoryId repositoryId) {
        for (RepositoryMutationListener listener : mutationListeners) {
            listener.beforeMutation(repositoryId);
        }
    }

    private void notifyAfterPublication(RepositorySnapshot snapshot) {
        try {
            for (RepositorySnapshotPublicationListener listener : publicationListeners) {
                listener.afterPublication(snapshot);
            }
        } catch (RuntimeException exception) {
            throw new RepositoryMutationException("repository publication failed");
        }
    }

    private <T> T withMutationLogging(
            RepositoryId repositoryId, String operation, Supplier<T> mutation) {
        try {
            return mutation.get();
        } catch (RepositoryMutationException exception) {
            log.warn(
                    "phase=repository-mutation outcome=failed repoId={} operation={} exceptionType={}",
                    repositoryId.value(), operation, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private <T> T withReadLock(RepositoryRuntime runtime, Supplier<T> operation) {
        Lock readLock = runtime.lock().readLock();
        acquireOrBusy(readLock, runtime.repositoryId());
        try {
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    private <T> T withWriteLock(RepositoryRuntime runtime, Supplier<T> operation) {
        Lock writeLock = runtime.lock().writeLock();
        acquireOrBusy(writeLock, runtime.repositoryId());
        try {
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void acquireOrBusy(Lock lock, RepositoryId repositoryId) {
        try {
            boolean acquired = lock.tryLock(lockTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new RepositoryBusyException(
                        "repository lock timed out: " + repositoryId.value());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryBusyException(
                    "repository lock interrupted: " + repositoryId.value());
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private boolean isExpectedSnapshotFailure(RuntimeException exception) {
        return exception instanceof ExpectedFailure
                || exception instanceof ImmutableFixtureException
                || exception instanceof InvalidRepositoryIdException
                || exception instanceof RepositoryBusyException
                || exception instanceof RepositoryMutationException
                || exception instanceof RepositoryNotFoundException
                || exception instanceof RepositoryNotReadyException
                || exception instanceof RepositoryRevisionMismatchException;
    }
}
