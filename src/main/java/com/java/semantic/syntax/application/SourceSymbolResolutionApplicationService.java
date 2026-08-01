package com.java.semantic.syntax.application;

import com.java.semantic.config.SourceSymbolResolutionProperties;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** fixed snapshot 內協調 resolver、bounded retry 與 executable follow-up */
@Slf4j
public final class SourceSymbolResolutionApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;

    private final SourceSymbolResolver resolver;

    private final DiscoveryFollowUpFactory followUpFactory;

    private final SourceSymbolResolutionProperties properties;

    public SourceSymbolResolutionApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SourceSymbolResolver resolver,
            DiscoveryFollowUpFactory followUpFactory,
            SourceSymbolResolutionProperties properties) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.resolver = Objects.requireNonNull(resolver, "resolver is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.properties = Objects.requireNonNull(properties, "properties are required");
    }

    /** expected revision 的 snapshot authority 內完成一次 source-symbol resolution */
    public RevisionBoundSourceSymbolResolution resolve(SourceSymbolResolutionQuery query) {
        SourceSymbolResolutionQuery request = Objects.requireNonNull(query, "query is required");
        long startedAt = System.nanoTime();
        return repositoryApplicationService.withSnapshot(
                request.repositoryId(),
                Optional.of(request.expectedRevision()),
                snapshot -> resolveSnapshot(snapshot, request, startedAt));
    }

    private RevisionBoundSourceSymbolResolution resolveSnapshot(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            long startedAt) {
        SourceSymbolResolution raw = resolver.resolve(snapshot, query);
        List<NavigableSourceContextCandidate> completeContexts = contextCandidates(
                snapshot, query, raw.contextCandidates());
        int totalCount = completeContexts.size();
        int candidateLimit = properties.contextCandidateLimit();
        List<NavigableSourceContextCandidate> returnedContexts = completeContexts.stream()
                .limit(candidateLimit)
                .toList();
        List<NavigableSourceSymbolCandidate> candidates = candidates(snapshot, query, raw);
        SourceContextCandidateLimits limits = new SourceContextCandidateLimits(
                candidateLimit,
                returnedContexts.size(),
                totalCount,
                totalCount > candidateLimit);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("phase=source-symbol-resolution repoId={} expectedRevision={} contextKind={} "
                        + "selectedSourceFile={} selectedSourceBytes={} matchingSymbolCount={} "
                        + "resolutionStatus={} durationMs={}",
                query.repositoryId().value(),
                query.expectedRevision().value(),
                query.context().method().isPresent() ? "METHOD" : "SOURCE_TYPE",
                raw.selectedSourceFile().orElse("-"),
                raw.selectedSourceBytes(),
                raw.matchingSymbolCount(),
                raw.status(),
                durationMs);
        return new RevisionBoundSourceSymbolResolution(
                snapshot.repositoryId(),
                snapshot.revision(),
                raw.status(),
                returnedContexts,
                limits,
                candidates,
                raw.issueSummaries());
    }

    private List<NavigableSourceContextCandidate> contextCandidates(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            List<SourceContextCandidate> rawCandidates) {
        List<NavigableSourceContextCandidate> candidates = new ArrayList<>();
        for (SourceContextCandidate candidate : rawCandidates) {
            candidates.add(navigableContextCandidate(snapshot, query, candidate));
        }
        return List.copyOf(candidates);
    }

    private NavigableSourceContextCandidate navigableContextCandidate(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            SourceContextCandidate candidate) {
        DiscoveryFollowUp retry = switch (candidate) {
            case SourceTypeContextCandidate type -> followUpFactory.forSourceTypeContextRetry(
                    snapshot.repositoryId(), snapshot.revision(), query, type.sourceFile());
            case SourceMethodContextCandidate method -> followUpFactory.forSourceMethodContextRetry(
                    snapshot.repositoryId(), snapshot.revision(), query, method.target());
        };
        return new NavigableSourceContextCandidate(candidate, retry);
    }

    private List<NavigableSourceSymbolCandidate> candidates(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            SourceSymbolResolution raw) {
        List<NavigableSourceSymbolCandidate> candidates = new ArrayList<>();
        for (SourceSymbolCandidate candidate : raw.candidates()) {
            candidates.add(navigableCandidate(snapshot, query, raw.status(), candidate));
        }
        return List.copyOf(candidates);
    }

    private NavigableSourceSymbolCandidate navigableCandidate(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            SourceSymbolResolutionStatus status,
            SourceSymbolCandidate candidate) {
        return new NavigableSourceSymbolCandidate(candidate, followUps(snapshot, query, status, candidate));
    }

    private List<DiscoveryFollowUp> followUps(
            RepositorySnapshot snapshot,
            SourceSymbolResolutionQuery query,
            SourceSymbolResolutionStatus status,
            SourceSymbolCandidate candidate) {
        if (status == SourceSymbolResolutionStatus.AMBIGUOUS_SYMBOL
                || status == SourceSymbolResolutionStatus.AMBIGUOUS_OCCURRENCE) {
            return List.of(followUpFactory.forSourceSymbolCandidateRetry(
                    snapshot.repositoryId(), snapshot.revision(), query, candidate));
        }
        if (status != SourceSymbolResolutionStatus.RESOLVED) {
            return List.of();
        }
        return switch (candidate) {
            case SourceSymbolCandidate.Method method -> followUpFactory.forMethod(
                    snapshot.repositoryId(), snapshot.revision(), method.identity());
            case SourceSymbolCandidate.SourceType type -> followUpFactory.forSourceType(
                    snapshot.repositoryId(), snapshot.revision(), type.identity().sourceFile(),
                    type.identity().fullyQualifiedName());
            case SourceSymbolCandidate.VariableLike variable -> followUpFactory.forResolvedFieldType(
                    snapshot.repositoryId(), snapshot.revision(), variable.resolvedType());
            case SourceSymbolCandidate.StaticConstant constant -> followUpFactory.forResolvedFieldType(
                    snapshot.repositoryId(), snapshot.revision(), constant.resolvedType());
        };
    }
}
