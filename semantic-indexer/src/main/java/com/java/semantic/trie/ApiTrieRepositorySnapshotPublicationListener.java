package com.java.semantic.trie;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.java.semantic.repository.port.RepositorySnapshotPublicationListener;
import com.java.semantic.syntax.application.EntryPointDiscoveryFilter;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;

@Component
public final class ApiTrieRepositorySnapshotPublicationListener
        implements RepositoryMutationListener, RepositorySnapshotPublicationListener {

    private final ApiTrieService apiTrieService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final EntryPointDiscoveryFilter discoveryFilter;

    public ApiTrieRepositorySnapshotPublicationListener(
            ApiTrieService apiTrieService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            EntryPointDiscoveryFilter discoveryFilter) {
        this.apiTrieService = Objects.requireNonNull(apiTrieService, "apiTrieService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.discoveryFilter = Objects.requireNonNull(discoveryFilter, "discoveryFilter is required");
    }

    @Override
    public void beforeMutation(RepositoryId repositoryId) {
        apiTrieService.clear(repositoryId);
    }

    @Override
    public void beforePublication(RepositoryId repositoryId) {
        apiTrieService.clear(repositoryId);
    }

    @Override
    public void afterPublication(RepositorySnapshot snapshot) {
        RepositorySyntax extracted = repositorySyntaxProvider.get(snapshot);
        RepositorySyntax filtered = discoveryFilter.filter(
                snapshot.repositoryId(), extracted, EnumSet.of(EntryPointType.API));
        apiTrieService.reload(snapshot, filtered);
    }
}
