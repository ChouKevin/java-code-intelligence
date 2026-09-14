package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.List;

/** JGit-produced direct tree change with its UTF-8 patch held as independently bounded chunks. */
public record GitComparisonChange(String changeId, GitChangeKind kind, String oldPath, String newPath, String oldMode,
                                  String newMode, String oldBlobId, String newBlobId, List<String> patchChunks, String diffStatus) {
    public GitComparisonChange(String changeId, GitChangeKind kind, String oldPath, String newPath, String oldMode,
                               String newMode, String oldBlobId, String newBlobId, String patch, String diffStatus) {
        this(changeId, kind, oldPath, newPath, oldMode, newMode, oldBlobId, newBlobId,
                Objects.requireNonNullElse(patch, "").isEmpty() ? List.of() : List.of(Objects.requireNonNullElse(patch, "")), diffStatus);
    }

    public GitComparisonChange {
        changeId = ModelValidation.requiredText(changeId, "git change id");
        kind = Objects.requireNonNull(kind, "git change kind is required");
        oldPath = Objects.requireNonNullElse(oldPath, "");
        newPath = Objects.requireNonNullElse(newPath, "");
        oldMode = Objects.requireNonNullElse(oldMode, "");
        newMode = Objects.requireNonNullElse(newMode, "");
        oldBlobId = Objects.requireNonNullElse(oldBlobId, "");
        newBlobId = Objects.requireNonNullElse(newBlobId, "");
        patchChunks = List.copyOf(Objects.requireNonNull(patchChunks, "git patch chunks are required"));
        if (patchChunks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("git patch chunks must not contain null");
        }
        diffStatus = ModelValidation.requiredText(diffStatus, "git diff status");
    }

    public String patch() {
        return String.join("", patchChunks);
    }
}
