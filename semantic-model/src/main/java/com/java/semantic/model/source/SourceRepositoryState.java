package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record SourceRepositoryState(int formatVersion, String repositoryId, Optional<CurrentPublication> current,
        Map<String, PreparedRevision> published, PreparationStatus preparation) {

    public static final String READ_LOCK_FILE_NAME = "read.lock";

    public SourceRepositoryState {
        ModelValidation.require(formatVersion == SourceRevisionManifest.FORMAT_VERSION,
                "unsupported source state format version");
        repositoryId = new RepositoryId(repositoryId).value();
        current = Objects.requireNonNull(current, "current publication");
        published = Map.copyOf(Objects.requireNonNull(published, "published revisions"));
        preparation = Objects.requireNonNull(preparation, "preparation status");
        for (Map.Entry<String, PreparedRevision> entry : published.entrySet()) {
            PreparedRevision receipt = entry.getValue();
            ModelValidation.require(entry.getKey().equals(receipt.context().revision())
                            && repositoryId.equals(receipt.context().repositoryId()),
                    "published receipts must match their repository and revision key");
        }
        if (current.isPresent()) {
            CurrentPublication pointer = current.orElseThrow();
            PreparedRevision receipt = published.get(pointer.revision());
            ModelValidation.require(Objects.nonNull(receipt)
                            && receipt.manifestDigest().equals(pointer.manifestDigest()),
                    "current publication must have its matching published receipt");
        }
    }

    public record CurrentPublication(String revision, String manifestDigest, String publicationJobId,
            Instant publishedAt) {

        public CurrentPublication {
            revision = RepositoryRevision.ofSha(revision).value();
            manifestDigest = ModelValidation.sha256(manifestDigest, "manifest digest");
            publicationJobId = ModelValidation.requiredText(publicationJobId, "publication job id");
            publishedAt = Objects.requireNonNull(publishedAt, "published at");
        }
    }

    public record PreparationStatus(PreparationPhase phase, Optional<String> jobId, Optional<String> failureCode) {

        public PreparationStatus {
            phase = Objects.requireNonNull(phase, "preparation phase");
            jobId = Objects.requireNonNull(jobId, "preparation job id");
            failureCode = Objects.requireNonNull(failureCode, "preparation failure code");
            jobId.ifPresent(value -> ModelValidation.requiredText(value, "preparation job id"));
            failureCode.ifPresent(value -> ModelValidation.requiredText(value, "preparation failure code"));
            ModelValidation.require((phase == PreparationPhase.IDLE) == jobId.isEmpty(),
                    "only idle preparation has no job identity");
            ModelValidation.require((phase == PreparationPhase.FAILED) == failureCode.isPresent(),
                    "only failed preparation has a failure code");
        }
    }

    public enum PreparationPhase {
        IDLE, ACCEPTED, RUNNING, COMPLETE, FAILED
    }
}
