package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.Assert;

/** Stable server-controlled identity for one owned JDT workspace directory. */
public record AnalysisWorkspaceKey(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        String jobId,
        String stage) {
    private static final Set<String> STAGES = Set.of("CODEBASE", "BEFORE", "AFTER");

    public AnalysisWorkspaceKey {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        Assert.hasText(jobId, "jobId is required");
        Assert.isTrue(STAGES.contains(stage), "stage must be CODEBASE, BEFORE, or AFTER");
        Assert.isTrue(isSafePathSegment(jobId), "jobId must be a safe path segment");
    }

    private static boolean isSafePathSegment(String value) {
        return !value.contains("/") && !value.contains("\\") && !value.contains(".") && !value.isBlank();
    }
}
