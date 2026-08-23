package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 在固定儲存庫快照內執行事件監聽器探索的應用服務
 *
 * 每次探索都重新解析儲存庫，不保留快取
 */
@Service
public final class EventListenerDiscoveryApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final EventListenerDiscoveryPolicy discoveryPolicy;

    @Autowired
    public EventListenerDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider) {
        this(repositoryApplicationService, repositorySyntaxProvider, new EventListenerDiscoveryPolicy());
    }

    EventListenerDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            EventListenerDiscoveryPolicy discoveryPolicy) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.discoveryPolicy = Objects.requireNonNull(discoveryPolicy, "discoveryPolicy is required");
    }

    public RevisionBoundEventListenerDiscovery discover(EventListenerDiscoveryQuery query) {
        Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                query.repositoryId(),
                Optional.of(query.expectedRevision()),
                snapshot -> discoverSnapshot(snapshot, query));
    }

    private RevisionBoundEventListenerDiscovery discoverSnapshot(
            RepositorySnapshot snapshot, EventListenerDiscoveryQuery query) {
        RepositorySyntax syntax = repositorySyntaxProvider.get(snapshot);
        EventListenerDiscoveryPage discovery = discoveryPolicy.discover(
                syntax, query.eventType(), query.offset(), query.limit());
        return new RevisionBoundEventListenerDiscovery(
                snapshot.repositoryId(), snapshot.revision(), query.eventType(), discovery);
    }
}
