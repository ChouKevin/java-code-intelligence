package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.Arrays;
import java.util.Objects;

/** One exact tracked-tree entry supplied by the Indexer Git adapter before durable publication. */
public record GitSnapshotEntry(String path, String mode, String blobId, GitFileContentStatus contentStatus, long byteLength, byte[] bytes,
                               byte[] rawPath) {
    public GitSnapshotEntry(String path, String mode, String blobId, GitFileContentStatus contentStatus, byte[] bytes) {
        this(path, mode, blobId, contentStatus, Objects.requireNonNull(bytes, "git snapshot bytes are required").length, bytes);
    }

    public GitSnapshotEntry(String path, String mode, String blobId, GitFileContentStatus contentStatus, long byteLength, byte[] bytes) {
        this(path, mode, blobId, contentStatus, byteLength, bytes, path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public GitSnapshotEntry {
        path = ModelValidation.requiredText(path, "git snapshot path");
        mode = ModelValidation.requiredText(mode, "git file mode");
        blobId = ModelValidation.requiredText(blobId, "git blob id");
        contentStatus = Objects.requireNonNull(contentStatus, "git file content status is required");
        if (byteLength < 0L) {
            throw new IllegalArgumentException("git snapshot byte length must not be negative");
        }
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "git snapshot bytes are required"), bytes.length);
        rawPath = Arrays.copyOf(Objects.requireNonNull(rawPath, "git snapshot raw path is required"), rawPath.length);
        if (contentStatus == GitFileContentStatus.TEXT && byteLength != bytes.length) {
            throw new IllegalArgumentException("text snapshot byte length must match stored bytes");
        }
    }

    @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    @Override public byte[] rawPath() { return Arrays.copyOf(rawPath, rawPath.length); }
}
