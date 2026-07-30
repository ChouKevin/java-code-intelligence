package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.springframework.util.Assert;

import java.util.Objects;

/** 綁定儲存庫版本的事件監聽器探索請求 */
public record EventListenerDiscoveryQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        String eventType,
        int offset,
        int limit) {

    public EventListenerDiscoveryQuery {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Assert.hasText(eventType, "eventType is required");
        Assert.isTrue(offset >= 0, "offset must not be negative");
        Assert.isTrue(limit >= EventListenerDiscoveryConstraints.MIN_LIMIT
                        && limit <= EventListenerDiscoveryConstraints.MAX_LIMIT,
                "limit must be within discovery bounds");
    }

    public EventListenerDiscoveryQuery(
            RepositoryId repositoryId, RepositoryRevision expectedRevision, String eventType, int offset) {
        this(repositoryId, expectedRevision, eventType, offset, EventListenerDiscoveryConstraints.DEFAULT_LIMIT);
    }
}
