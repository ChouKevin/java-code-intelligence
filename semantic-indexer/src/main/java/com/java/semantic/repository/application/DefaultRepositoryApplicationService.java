package com.java.semantic.repository.application;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.repository.InvalidRepositoryIdException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coordinates read-side repository snapshots through each runtime's fair lock. */
@Service
@Slf4j
public class DefaultRepositoryApplicationService implements RepositoryApplicationService {

    private final RepositoryRuntimeRegistry registry;
    private final Duration lockTimeout;

    public DefaultRepositoryApplicationService(
            RepositoryRuntimeRegistry registry,
            RepositoryProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.lockTimeout = Objects.requireNonNull(
                properties.getRepositoryLockTimeout(), "repositoryLockTimeout is required");
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

    private void requireRevision(RepositorySnapshot snapshot, RepositoryRevision expected) {
        if (!snapshot.revision().equals(expected)) {
            throw new RepositoryRevisionMismatchException(expected, snapshot.revision());
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
                || exception instanceof InvalidRepositoryIdException
                || exception instanceof RepositoryBusyException
                || exception instanceof RepositoryMutationException
                || exception instanceof RepositoryNotFoundException
                || exception instanceof RepositoryNotReadyException
                || exception instanceof RepositoryRevisionMismatchException;
    }
}
