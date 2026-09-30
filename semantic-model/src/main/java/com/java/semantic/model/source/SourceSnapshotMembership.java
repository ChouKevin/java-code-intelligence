package com.java.semantic.model.source;

import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;

/** Same-commit immutable Git evidence sealed into a semantic generation. */
public record SourceSnapshotMembership(GitSnapshotId snapshotId, RepositoryRevision revision,
        String policyFingerprint, String contentDigest) {
    public SourceSnapshotMembership {
        Objects.requireNonNull(snapshotId, "snapshot id is required");
        Objects.requireNonNull(revision, "revision is required");
        policyFingerprint = ModelValidation.sha256(policyFingerprint, "source policy fingerprint");
        contentDigest = ModelValidation.sha256(contentDigest, "source snapshot digest");
    }
}
