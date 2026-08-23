package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.ImplementationCandidate;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.List;
import java.util.Objects;

/** 同一 revision 內記憶體探索出的實作候選，呼叫者須檢查 truncated */
public record RevisionBoundMethodImplementations(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        MethodTarget requestedTarget,
        List<ImplementationCandidate> candidates,
        MethodImplementationLimits limits,
        List<MethodImplementationIssueReason> issues) {

    public RevisionBoundMethodImplementations {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        requestedTarget = Objects.requireNonNull(requestedTarget, "requestedTarget is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        limits = Objects.requireNonNull(limits, "limits are required");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
        if (candidates.size() != limits.returnedCount()) {
            throw new IllegalArgumentException("candidates must match returnedCount");
        }
    }
}
