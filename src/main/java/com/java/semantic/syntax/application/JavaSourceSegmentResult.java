package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 固定 revision 內已受 byte bound 限制的 Java source segment */
public record JavaSourceSegmentResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SourceRange requestedRange,
        SourceRange contentRange,
        String content,
        boolean contextTruncated,
        int returnedUtf8Bytes) {

    public JavaSourceSegmentResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        requestedRange = Objects.requireNonNull(requestedRange, "requestedRange is required");
        contentRange = Objects.requireNonNull(contentRange, "contentRange is required");
        content = Objects.requireNonNull(content, "content is required");
        if (returnedUtf8Bytes != content.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("returnedUtf8Bytes must match content");
        }
    }
}
