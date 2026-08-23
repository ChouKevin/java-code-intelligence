package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.springframework.util.Assert;

import java.util.Objects;

/** 綁定實際分析版本的事件監聽器探索結果 */
public record RevisionBoundEventListenerDiscovery(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        String requestedEventType,
        EventListenerDiscoveryPage discovery) {

    public RevisionBoundEventListenerDiscovery {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        Assert.hasText(requestedEventType, "requestedEventType is required");
        Objects.requireNonNull(discovery, "discovery is required");
    }
}
