package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.List;

/** JGit-produced direct tree change with its UTF-8 patch held as independently bounded chunks. */
public record GitComparisonChange(String changeId, GitChangeKind kind, String oldPath, String newPath, String oldMode,
                                  String newMode, String oldBlobId, String newBlobId, List<String> patchChunks, String diffStatus,
                                  byte[] oldRawPath, byte[] newRawPath) {
    public GitComparisonChange(String changeId, GitChangeKind kind, String oldPath, String newPath, String oldMode,
                               String newMode, String oldBlobId, String newBlobId, String patch, String diffStatus) {
        this(changeId, kind, oldPath, newPath, oldMode, newMode, oldBlobId, newBlobId,
                Objects.requireNonNullElse(patch, "").isEmpty() ? List.of() : List.of(Objects.requireNonNullElse(patch, "")), diffStatus,
                Objects.requireNonNullElse(oldPath, "").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Objects.requireNonNullElse(newPath, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public GitComparisonChange(String changeId, GitChangeKind kind, String oldPath, String newPath, String oldMode,
                               String newMode, String oldBlobId, String newBlobId, List<String> patchChunks, String diffStatus) {
        this(changeId, kind, oldPath, newPath, oldMode, newMode, oldBlobId, newBlobId, patchChunks, diffStatus,
                Objects.requireNonNullElse(oldPath, "").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Objects.requireNonNullElse(newPath, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        oldRawPath = Objects.requireNonNull(oldRawPath, "git old raw path is required").clone();
        newRawPath = Objects.requireNonNull(newRawPath, "git new raw path is required").clone();
    }

    @Override
    public byte[] oldRawPath() { return oldRawPath.clone(); }

    @Override
    public byte[] newRawPath() { return newRawPath.clone(); }

    public String patch() {
        return String.join("", patchChunks);
    }
}
