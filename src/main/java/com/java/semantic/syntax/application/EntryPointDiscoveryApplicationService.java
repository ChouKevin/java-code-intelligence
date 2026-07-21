package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public final class EntryPointDiscoveryApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final EntryPointDiscoveryFilter discoveryFilter;

    public EntryPointDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            EntryPointDiscoveryFilter discoveryFilter) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.discoveryFilter = Objects.requireNonNull(
                discoveryFilter, "discoveryFilter is required");
    }

    public RevisionBoundEntryPoints list(
            RepositoryId repositoryId,
            Set<EntryPointType> requestedTypes) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(requestedTypes, "requestedTypes is required");
        Set<EntryPointType> immutableTypes = Set.copyOf(requestedTypes);
        return repositoryApplicationService.withSnapshot(
                repositoryId,
                Optional.empty(),
                snapshot -> listSnapshot(snapshot, immutableTypes));
    }

    private RevisionBoundEntryPoints listSnapshot(
            RepositorySnapshot snapshot,
            Set<EntryPointType> requestedTypes) {
        RepositorySyntax extracted = syntaxExtractionService.extract(snapshot.root());
        RepositorySyntax filtered = discoveryFilter.filter(
                snapshot.repositoryId(), extracted, requestedTypes);
        return new RevisionBoundEntryPoints(
                snapshot.repositoryId(), snapshot.revision(), filtered.entryPoints());
    }
}
