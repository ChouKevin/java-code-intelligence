package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public final class EntryPointDiscoveryApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final EntryPointDiscoveryFilter discoveryFilter;

    public EntryPointDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            EntryPointDiscoveryFilter discoveryFilter) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.discoveryFilter = Objects.requireNonNull(
                discoveryFilter, "discoveryFilter is required");
    }

    public RevisionBoundEntryPoints list(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            Set<EntryPointType> requestedTypes) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Objects.requireNonNull(requestedTypes, "requestedTypes is required");
        Set<EntryPointType> immutableTypes = Set.copyOf(requestedTypes);
        return repositoryApplicationService.withSnapshot(
                repositoryId,
                Optional.of(expectedRevision),
                snapshot -> listSnapshot(snapshot, immutableTypes));
    }

    private RevisionBoundEntryPoints listSnapshot(
            RepositorySnapshot snapshot,
            Set<EntryPointType> requestedTypes) {
        RepositorySyntax extracted = repositorySyntaxProvider.get(snapshot);
        RepositorySyntax filtered = discoveryFilter.filter(
                snapshot.repositoryId(), extracted, requestedTypes);
        List<EntryPointClass> sortedEntryPoints = filtered.entryPoints().stream()
                .sorted(Comparator.comparing((EntryPointClass entryPoint) -> entryPoint.sourceType().sourceFile())
                        .thenComparing(entryPoint -> entryPoint.sourceType().javaType().fullyQualifiedName()))
                .toList();
        return new RevisionBoundEntryPoints(
                snapshot.repositoryId(), snapshot.revision(), sortedEntryPoints);
    }
}
