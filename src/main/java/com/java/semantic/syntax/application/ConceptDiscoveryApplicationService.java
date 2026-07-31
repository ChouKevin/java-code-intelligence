package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** 在固定儲存庫快照內投影並分頁結構化概念 */
public final class ConceptDiscoveryApplicationService {

    private static final long STAGE_NOT_STARTED = -1;

    private static final Logger log = LoggerFactory.getLogger(ConceptDiscoveryApplicationService.class);

    private static final Comparator<ConceptCatalogEntry> CANONICAL_ORDER = Comparator
            .comparing(ConceptCatalogEntry::kind)
            .thenComparing(ConceptCatalogEntry::canonicalValue)
            .thenComparing(entry -> entry.identity().canonicalOrderKey());

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final StructuredConceptCatalogProjector catalogProjector;
    private final ConceptSearchMatcher searchMatcher;

    public ConceptDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            StructuredConceptCatalogProjector catalogProjector,
            ConceptSearchMatcher searchMatcher) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.catalogProjector = Objects.requireNonNull(catalogProjector, "catalogProjector is required");
        this.searchMatcher = Objects.requireNonNull(searchMatcher, "searchMatcher is required");
    }

    /** 在預期 revision 的讀鎖快照內執行一次不快取的概念搜尋 */
    public ConceptSearchResult search(ConceptSearchQuery query) {
        ConceptSearchQuery searchQuery = Objects.requireNonNull(query, "query is required");
        String correlationId = requestIdFromMdc();
        ConceptPhaseTiming timing = new ConceptPhaseTiming(searchQuery.expectedRevision().value());
        try {
            Set<ConceptKind> activeKinds = Set.copyOf(catalogProjector.supportedKinds());
            Set<ConceptKind> unavailableKinds = searchQuery.kinds().stream()
                    .filter(kind -> !activeKinds.contains(kind))
                    .collect(Collectors.toUnmodifiableSet());
            if (!unavailableKinds.isEmpty()) {
                throw new ConceptKindUnavailableException(unavailableKinds, activeKinds);
            }
            return repositoryApplicationService.withSnapshot(
                    searchQuery.repositoryId(),
                    Optional.of(searchQuery.expectedRevision()),
                    snapshot -> searchSnapshot(
                            snapshot,
                            searchQuery,
                            correlationId,
                            timing,
                            activeKinds));
        } catch (RuntimeException | Error exception) {
            emitPhaseEvent(
                    correlationId,
                    searchQuery,
                    timing,
                    0,
                    0,
                    0,
                    "FAILED");
            throw exception;
        }
    }

    private ConceptSearchResult searchSnapshot(
            RepositorySnapshot snapshot,
            ConceptSearchQuery query,
            String requestId,
            ConceptPhaseTiming timing,
            Set<ConceptKind> activeKinds) {
        timing.snapshotAcquired(snapshot.revision().value());
        timing.syntaxExtractionStarted();
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        timing.syntaxExtractionCompleted();
        timing.catalogProjectionStarted();
        StructuredConceptCatalog catalog = catalogProjector.project(syntax);
        timing.catalogProjectionCompleted();
        timing.matchingStarted();
        List<ConceptCatalogEntry> matches = catalog.entries().stream()
                .filter(entry -> query.kinds().contains(entry.kind()))
                .filter(entry -> matchesPackage(entry.packageName(), query.packagePrefix()))
                .filter(entry -> searchMatcher.matches(entry, query.terms()))
                .sorted(CANONICAL_ORDER)
                .toList();
        int start = Math.min(query.offset(), matches.size());
        int end = (int) Math.min((long) start + query.limit(), matches.size());
        List<ConceptCatalogEntry> candidates = List.copyOf(matches.subList(start, end));
        boolean hasMore = end < matches.size();
        ConceptPage page = new ConceptPage(query.offset(), query.limit(), candidates.size(), matches.size(), hasMore);
        Optional<ConceptSearchQuery> nextPageQuery = hasMore
                ? Optional.of(query.nextPage(end))
                : Optional.empty();
        ConceptSearchResult result = new ConceptSearchResult(
                snapshot.repositoryId(),
                snapshot.revision(),
                query.terms(),
                query.kinds().stream().sorted().toList(),
                activeKinds.stream().sorted().toList(),
                candidates,
                page,
                syntax.extractionOutcomes(),
                catalog.issueSummaries(),
                nextPageQuery);
        timing.matchingCompleted();
        emitPhaseEvent(
                requestId,
                query,
                timing,
                result.page().returnedCount(),
                result.page().totalCount(),
                result.issueSummaries().stream().mapToInt(ConceptIssueSummary::count).sum(),
                "COMPLETED");
        return result;
    }

    private static String requestIdFromMdc() {
        String requestId = MDC.get("requestId");
        return StringUtils.hasText(requestId) ? requestId : "";
    }

    private static long elapsedMillis(long startedAt) {
        return elapsedMillis(startedAt, System.nanoTime());
    }

    static long elapsedMillis(long startedAt, long currentTime) {
        return TimeUnit.NANOSECONDS.toMillis(currentTime - startedAt);
    }

    private static void emitPhaseEvent(
            String requestId,
            ConceptSearchQuery query,
            ConceptPhaseTiming timing,
            int returnedCount,
            long pageTotalCount,
            int issueCount,
            String outcome) {
        if (!timing.markEventEmitted()) {
            return;
        }
        log.info(
                "concept_discovery_phase requestId={} repoId={} repositoryRevision={} matchModes={} "
                        + "requestedKinds={} termCount={} normalizedTerms={} offset={} limit={} "
                        + "returnedCount={} pageTotalCount={} snapshotAcquireMillis={} syntaxExtractionMillis={} "
                        + "catalogProjectionMillis={} matchingMillis={} issueCount={} outcome={}",
                requestId,
                query.repositoryId().value(),
                timing.repositoryRevision(),
                query.terms().stream().map(ConceptSearchTerm::matchMode).toList(),
                query.kinds().stream().sorted().toList(),
                query.terms().size(),
                query.terms().stream().map(ConceptSearchTerm::value).toList(),
                query.offset(),
                query.limit(),
                returnedCount,
                pageTotalCount,
                timing.snapshotAcquireMillis(),
                timing.syntaxExtractionMillis(),
                timing.catalogProjectionMillis(),
                timing.matchingMillis(),
                issueCount,
                outcome);
    }

    /** packagePrefix 代表指定 package subtree，包含本身與所有子 package */
    private static boolean matchesPackage(String packageName, Optional<String> packagePrefix) {
        return packagePrefix.map(prefix -> packageName.equals(prefix)
                || packageName.startsWith(prefix + ".")).orElse(true);
    }

    /** 保存單次概念探索各階段的單調時鐘狀態 */
    private static final class ConceptPhaseTiming {

        private final long snapshotAcquireStartedAt;
        private String repositoryRevision;
        private boolean snapshotAcquireStarted;
        private boolean snapshotAcquireCompleted;
        private long snapshotAcquireMillis;
        private boolean syntaxExtractionStarted;
        private boolean syntaxExtractionCompleted;
        private long syntaxExtractionStartedAt;
        private long syntaxExtractionMillis;
        private boolean catalogProjectionStarted;
        private boolean catalogProjectionCompleted;
        private long catalogProjectionStartedAt;
        private long catalogProjectionMillis;
        private boolean matchingStarted;
        private boolean matchingCompleted;
        private long matchingStartedAt;
        private long matchingMillis;
        private boolean eventEmitted;

        ConceptPhaseTiming(String expectedRevision) {
            repositoryRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
            snapshotAcquireStartedAt = System.nanoTime();
            snapshotAcquireStarted = true;
        }

        void snapshotAcquired(String actualRevision) {
            repositoryRevision = Objects.requireNonNull(actualRevision, "actualRevision is required");
            snapshotAcquireMillis = elapsedMillis(snapshotAcquireStartedAt);
            snapshotAcquireCompleted = true;
        }

        void syntaxExtractionStarted() {
            syntaxExtractionStartedAt = System.nanoTime();
            syntaxExtractionStarted = true;
        }

        void syntaxExtractionCompleted() {
            syntaxExtractionMillis = elapsedMillis(syntaxExtractionStartedAt);
            syntaxExtractionCompleted = true;
        }

        void catalogProjectionStarted() {
            catalogProjectionStartedAt = System.nanoTime();
            catalogProjectionStarted = true;
        }

        void catalogProjectionCompleted() {
            catalogProjectionMillis = elapsedMillis(catalogProjectionStartedAt);
            catalogProjectionCompleted = true;
        }

        void matchingStarted() {
            matchingStartedAt = System.nanoTime();
            matchingStarted = true;
        }

        void matchingCompleted() {
            matchingMillis = elapsedMillis(matchingStartedAt);
            matchingCompleted = true;
        }

        String repositoryRevision() {
            return repositoryRevision;
        }

        long snapshotAcquireMillis() {
            return elapsedStage(
                    snapshotAcquireStarted,
                    snapshotAcquireCompleted,
                    snapshotAcquireStartedAt,
                    snapshotAcquireMillis);
        }

        long syntaxExtractionMillis() {
            return elapsedStage(
                    syntaxExtractionStarted,
                    syntaxExtractionCompleted,
                    syntaxExtractionStartedAt,
                    syntaxExtractionMillis);
        }

        long catalogProjectionMillis() {
            return elapsedStage(
                    catalogProjectionStarted,
                    catalogProjectionCompleted,
                    catalogProjectionStartedAt,
                    catalogProjectionMillis);
        }

        long matchingMillis() {
            return elapsedStage(
                    matchingStarted,
                    matchingCompleted,
                    matchingStartedAt,
                    matchingMillis);
        }

        boolean markEventEmitted() {
            if (eventEmitted) {
                return false;
            }
            eventEmitted = true;
            return true;
        }

        private static long elapsedStage(
                boolean started,
                boolean completed,
                long startedAt,
                long completedMillis) {
            if (!started) {
                return STAGE_NOT_STARTED;
            }
            if (completed) {
                return completedMillis;
            }
            return elapsedMillis(startedAt);
        }
    }
}
