package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-side claim/revocation sequencing. Extraction is supplied by Task 6. */
@Component
public final class IndexJobWorker {
    private final IndexJobStore jobs;
    private final PublicationPort publication;

    public IndexJobWorker(IndexJobStore jobs, PublicationPort publication) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.publication = Objects.requireNonNull(publication, "publication is required");
    }

    public Optional<IndexJob> claim(IndexJobId jobId, String workerId, Duration claimLifetime) {
        return jobs.claim(jobId, workerId, claimLifetime);
    }

    public boolean renew(IndexJob job, Duration claimLifetime) {
        return jobs.renew(job, claimLifetime);
    }

    /** Revocation is intentionally before any terminal job mutation. */
    public boolean failOrCancel(IndexJob job) {
        boolean revoked = jobs.revoke(job);
        if (revoked) {
            return jobs.failAfterRevocation(job);
        }
        jobs.reconcileCommitted(job.repositoryId());
        return false;
    }

    public void expireClaims() {
        jobs.failExpiredClaims();
    }

    public boolean rollback(IndexJob job) {
        if (job.operation() != IndexJobOperation.ROLLBACK) {
            throw new IllegalArgumentException("job is not a rollback operation");
        }
        Optional<com.java.semantic.model.index.RollbackGenerationCommand> command = jobs.rollbackCommand(job);
        if (command.isEmpty()) {
            return false;
        }
        publication.rollback(command.orElseThrow());
        return jobs.reconcileCommitted(job.repositoryId()).isPresent();
    }

    /** Task 6 calls this only after sealing; it persists the exact intent before crossing the Task-4 boundary. */
    public boolean publishBuild(IndexJob job, ManifestDigest sealedManifestDigest) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(sealedManifestDigest, "sealed manifest digest is required");
        Optional<IndexPublicationIntent> intent = jobs.prepareBuildPublication(job, sealedManifestDigest);
        if (intent.isEmpty() || job.workerId().isEmpty() || job.fence().isEmpty()) {
            return false;
        }
        IndexPublicationIntent exactIntent = intent.orElseThrow();
        PublishGenerationCommand command = new PublishGenerationCommand(job.repositoryId(), exactIntent.targetRevision(),
                exactIntent.targetGenerationId(), job.id().value(), job.workerId().orElseThrow(), job.fence().orElseThrow(),
                exactIntent.expectedParent(), exactIntent.targetManifestDigest());
        publication.publish(command);
        return jobs.reconcileCommitted(job.repositoryId()).isPresent();
    }

    /** The callback must check the supplied guard before every externally visible write or publish. */
    public void runWithHeartbeat(IndexJob job, Duration heartbeatInterval, Duration claimLifetime,
                                 ScheduledExecutorService scheduler, LeaseWork work) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(heartbeatInterval, "heartbeat interval is required");
        Objects.requireNonNull(claimLifetime, "claim lifetime is required");
        Objects.requireNonNull(scheduler, "scheduler is required");
        Objects.requireNonNull(work, "work is required");
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        if (claimLifetime.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("claim lifetime must exceed heartbeat interval");
        }
        AtomicBoolean leaseHeld = new AtomicBoolean(true);
        AtomicBoolean workFinished = new AtomicBoolean(false);
        Object lifecycleLock = new Object();
        Thread workThread = Thread.currentThread();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            synchronized (lifecycleLock) {
                if (workFinished.get()) {
                    return;
                }
                boolean renewed;
                try {
                    renewed = renew(job, claimLifetime);
                } catch (RuntimeException exception) {
                    renewed = false;
                }
                if (!renewed) {
                    leaseHeld.set(false);
                    workThread.interrupt();
                }
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        try {
            LeaseGuard guard = () -> {
                if (!leaseHeld.get()) {
                    throw new ClaimLostException(job.id());
                }
            };
            guard.requireHeld();
            work.run(guard);
            guard.requireHeld();
        } finally {
            synchronized (lifecycleLock) {
                workFinished.set(true);
            }
            heartbeat.cancel(true);
        }
    }
}
