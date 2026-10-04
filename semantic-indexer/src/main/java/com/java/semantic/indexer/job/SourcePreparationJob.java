package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Private, durable original request and exact publication intent. */
public record SourcePreparationJob(int formatVersion, String jobId, String repositoryId, String requestId,
        Optional<String> requestedRevision, String defaultBranch, Phase phase, Instant acceptedAt,
        Optional<String> resolvedRevision, Optional<CurrentPublication> expectedCurrent,
        Optional<PreparedRevision> publication, Optional<String> failureCode) {
    public enum Phase { ACCEPTED, RUNNING, COMPLETE, FAILED }

    public SourcePreparationJob {
        if (formatVersion != SourceRevisionManifest.FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported job format");
        }
        new IndexJobId(jobId);
        new RepositoryId(repositoryId);
        new PreparationRequestId(requestId);
        requestedRevision = Objects.requireNonNull(requestedRevision, "requested revision");
        requestedRevision.ifPresent(RepositoryRevision::ofSha);
        if (Objects.requireNonNull(defaultBranch, "default branch").isBlank()) {
            throw new IllegalArgumentException("default branch is required");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(acceptedAt, "accepted at");
        resolvedRevision = Objects.requireNonNull(resolvedRevision, "resolved revision");
        resolvedRevision.ifPresent(RepositoryRevision::ofSha);
        expectedCurrent = Objects.requireNonNull(expectedCurrent, "expected current");
        publication = Objects.requireNonNull(publication, "publication");
        failureCode = Objects.requireNonNull(failureCode, "failure code");
        if ((phase == Phase.COMPLETE) != publication.isPresent() || (phase == Phase.FAILED) != failureCode.isPresent()) {
            throw new IllegalArgumentException("terminal job proof is inconsistent");
        }
    }

    public SourcePreparationJob withPhase(Phase next) {
        return new SourcePreparationJob(formatVersion, jobId, repositoryId, requestId, requestedRevision,
                defaultBranch, next, acceptedAt, resolvedRevision, expectedCurrent, publication, failureCode);
    }

    public SourcePreparationJob withRevision(RepositoryRevision revision) {
        if (resolvedRevision.isPresent() && !resolvedRevision.orElseThrow().equals(revision.value())) {
            throw new IllegalStateException("a pinned revision cannot change");
        }
        return new SourcePreparationJob(formatVersion, jobId, repositoryId, requestId, requestedRevision,
                defaultBranch, phase, acceptedAt, Optional.of(revision.value()), expectedCurrent, publication, failureCode);
    }

    public SourcePreparationJob completed(PreparedRevision receipt) {
        return new SourcePreparationJob(formatVersion, jobId, repositoryId, requestId, requestedRevision,
                defaultBranch, Phase.COMPLETE, acceptedAt, resolvedRevision, expectedCurrent, Optional.of(receipt), Optional.empty());
    }

    public SourcePreparationJob failed(String code) {
        return new SourcePreparationJob(formatVersion, jobId, repositoryId, requestId, requestedRevision,
                defaultBranch, Phase.FAILED, acceptedAt, resolvedRevision, expectedCurrent, Optional.empty(), Optional.of(code));
    }
}
