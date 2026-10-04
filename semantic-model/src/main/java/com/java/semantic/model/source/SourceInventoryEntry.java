package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;
import java.util.Optional;

public record SourceInventoryEntry(String path, EntryKind kind, Optional<EntryStatus> status, long byteLength,
        Optional<String> blobId, Optional<String> contentDigest) {

    public SourceInventoryEntry {
        path = SourcePathPolicy.requireFile(path);
        kind = Objects.requireNonNull(kind, "entry kind");
        status = Objects.requireNonNull(status, "entry status");
        blobId = Objects.requireNonNull(blobId, "blob id");
        contentDigest = Objects.requireNonNull(contentDigest, "content digest");
        ModelValidation.require(byteLength >= 0, "byte length must not be negative");
        blobId.ifPresent(RepositoryRevision::ofSha);
        contentDigest.ifPresent(value -> ModelValidation.sha256(value, "content digest"));
        if (kind == EntryKind.DIRECTORY) {
            ModelValidation.require(status.isEmpty() && byteLength == 0 && blobId.isEmpty() && contentDigest.isEmpty(),
                    "directory entries must not claim file content");
        } else {
            ModelValidation.require(status.isPresent(), "file entries require an explicit status");
            if (status.orElseThrow() == EntryStatus.TEXT) {
                ModelValidation.require(blobId.isPresent() && contentDigest.isPresent(),
                        "readable files require exact blob and content identity");
            }
        }
    }
}
