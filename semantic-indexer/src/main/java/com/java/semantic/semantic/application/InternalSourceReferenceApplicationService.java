package com.java.semantic.semantic.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.ExactSourceDeclarationResolver;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** 固定 revision 完成 exact reference 聚合後才投影 group 分頁 */
public final class InternalSourceReferenceApplicationService {

    private static final Logger log = LoggerFactory.getLogger(InternalSourceReferenceApplicationService.class);

    private static final int REPRESENTATIVE_LIMIT = 3;
    private static final Comparator<SyntaxPosition> POSITION_ORDER = Comparator
            .comparingInt(SyntaxPosition::line)
            .thenComparingInt(SyntaxPosition::character);
    private static final Comparator<SyntaxRange> RANGE_ORDER = Comparator
            .comparing(SyntaxRange::start, POSITION_ORDER)
            .thenComparing(SyntaxRange::end, POSITION_ORDER);
    private static final Comparator<LocalReference> LOCAL_REFERENCE_ORDER = Comparator
            .comparing(LocalReference::sourceFile)
            .thenComparing(LocalReference::range, RANGE_ORDER);
    private static final Comparator<InternalReferenceContext> CONTEXT_ORDER = Comparator
            .comparing(InternalReferenceContext::sourceFile)
            .thenComparing(InternalReferenceContext::kind)
            .thenComparing(InternalSourceReferenceApplicationService::ownerName)
            .thenComparing(InternalSourceReferenceApplicationService::methodName)
            .thenComparing(InternalSourceReferenceApplicationService::parameterTypes,
                    InternalSourceReferenceApplicationService::compareStrings);

    private final RepositoryApplicationService repositoryApplicationService;
    private final ExactSourceDeclarationResolver declarationResolver;
    private final JavaSemanticService semanticService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final InternalReferenceAnalysisCache cache;

    public InternalSourceReferenceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            ExactSourceDeclarationResolver declarationResolver,
            JavaSemanticService semanticService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            InternalReferenceAnalysisCache cache) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.declarationResolver = Objects.requireNonNull(declarationResolver, "declarationResolver is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.cache = Objects.requireNonNull(cache, "cache is required");
    }

    /** 在 repository read lock 內查詢完整分析並投影指定 group page */
    public InternalSourceReferenceResult find(InternalSourceReferenceQuery query) {
        InternalSourceReferenceQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        long startedAt = System.nanoTime();
        InternalSourceReferenceResult result = repositoryApplicationService.withSnapshot(
                requiredQuery.repositoryId(),
                Optional.of(requiredQuery.expectedRevision()),
                snapshot -> findSnapshot(snapshot, requiredQuery));
        logPerformance(requiredQuery, result, elapsedMillis(startedAt));
        return result;
    }

    private void logPerformance(
            InternalSourceReferenceQuery query,
            InternalSourceReferenceResult result,
            long durationMs) {
        log.info(
                "internal_reference_performance repoId={} expectedRevision={} targetKind={} cacheHit={} "
                        + "cacheStored={} cacheEntryWeight={} cacheLoadDurationMs={} rawLocationCount={} "
                        + "repositoryLocalReferenceCount={} hitFileCount={} totalGroupCount={} status={} durationMs={}",
                query.repositoryId().value(),
                query.expectedRevision().value(),
                targetKind(query.target()),
                result.cache().cacheHit(),
                result.cache().cacheStored(),
                result.cache().entryWeight(),
                result.cache().loadDurationMs(),
                result.rawLocationCount(),
                result.repositoryLocalReferenceCount(),
                result.hitFileCount(),
                result.totalGroupCount(),
                result.status(),
                durationMs);
    }

    private String targetKind(ExactSourceDeclarationTarget target) {
        return switch (target) {
            case ExactSourceDeclarationTarget.Type ignored -> "TYPE";
            case ExactSourceDeclarationTarget.Method ignored -> "METHOD";
            case ExactSourceDeclarationTarget.Member ignored -> "MEMBER";
        };
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private InternalSourceReferenceResult findSnapshot(
            RepositorySnapshot snapshot, InternalSourceReferenceQuery query) {
        InternalReferenceAnalysisCache.Key key = new InternalReferenceAnalysisCache.Key(
                snapshot.repositoryId(), snapshot.revision(), query.target());
        InternalReferenceAnalysisCache.LookupResult lookup = cache.lookup(
                key, () -> analyze(snapshot, query));
        InternalReferenceAnalysis analysis = lookup.value();
        int start = Math.min(query.offset(), analysis.groups().size());
        int end = Math.min(start + query.limit(), analysis.groups().size());
        List<InternalReferenceGroup> pageGroups = analysis.groups().subList(start, end);
        InternalReferencePage page = new InternalReferencePage(
                query.offset(),
                query.limit(),
                pageGroups.size(),
                analysis.groups().size(),
                end < analysis.groups().size());
        Duration loadDuration = lookup.loadDuration();
        InternalReferenceCacheMetadata cacheMetadata = new InternalReferenceCacheMetadata(
                lookup.cacheHit(),
                lookup.cacheStored(),
                lookup.entryWeight(),
                loadDuration.toMillis());
        return new InternalSourceReferenceResult(
                snapshot.repositoryId(),
                snapshot.revision(),
                analysis.targetDeclaration(),
                analysis.status(),
                analysis.totalReferenceCount(),
                pageGroups,
                page,
                analysis.issueSummaries(),
                cacheMetadata,
                analysis.rawLocationCount(),
                analysis.repositoryLocalReferenceCount(),
                analysis.hitFileCount(),
                analysis.groups().size());
    }

    private InternalReferenceAnalysis analyze(
            RepositorySnapshot snapshot, InternalSourceReferenceQuery query) {
        ExactSourceDeclaration declaration = declarationResolver.resolve(snapshot.root(), query.target())
                .orElseThrow(SourceDeclarationNotFoundException::new);
        SemanticReferenceAnchor anchor = new SemanticReferenceAnchor(
                declaration.target().sourceFile(),
                semanticPosition(declaration.identifierRange().start()));
        List<SemanticReferenceLocation> rawLocations = semanticService.findReferences(snapshot, anchor);
        EnumMap<InternalReferenceIssueCode, Integer> issueCounts = new EnumMap<>(InternalReferenceIssueCode.class);
        Set<LocalReference> deduplicated = new TreeSet<>(LOCAL_REFERENCE_ORDER);
        for (SemanticReferenceLocation location : rawLocations) {
            switch (location) {
                case SemanticReferenceLocation.LocalSource local -> addLocalReference(local, deduplicated, issueCounts);
                case SemanticReferenceLocation.OutsideRepository ignored -> {
                    // 已證實的 snapshot 外 reference 不影響 repository-local 完整性
                }
                case SemanticReferenceLocation.UnprovableUri ignored -> increment(
                        issueCounts, InternalReferenceIssueCode.REFERENCE_SOURCE_OUTSIDE_SNAPSHOT);
            }
        }
        LocalReference declarationIdentifier = new LocalReference(
                declaration.target().sourceFile(), declaration.identifierRange());
        deduplicated.remove(declarationIdentifier);
        int repositoryLocalReferenceCount = deduplicated.size();
        RepositorySyntax syntax = repositorySyntaxProvider.get(snapshot);
        Map<InternalReferenceContext, List<InternalReferenceOccurrence>> grouped = new LinkedHashMap<>();
        Set<String> hitFiles = new LinkedHashSet<>();
        int totalReferenceCount = 0;
        for (LocalReference reference : deduplicated) {
            if (!reference.sourceFile().endsWith(".java")) {
                increment(issueCounts, InternalReferenceIssueCode.REFERENCE_SOURCE_NOT_JAVA);
                continue;
            }
            totalReferenceCount++;
            hitFiles.add(reference.sourceFile());
            Optional<InternalReferenceContext> context = contextOf(syntax, reference);
            if (context.isEmpty()) {
                increment(issueCounts, InternalReferenceIssueCode.REFERENCE_CONTEXT_UNRESOLVED);
                continue;
            }
            grouped.computeIfAbsent(context.orElseThrow(), ignored -> new ArrayList<>())
                    .add(new InternalReferenceOccurrence(reference.range()));
        }
        List<InternalReferenceGroup> groups = groups(grouped);
        List<InternalReferenceIssueSummary> issues = issueCounts.entrySet().stream()
                .map(entry -> new InternalReferenceIssueSummary(entry.getKey(), entry.getValue()))
                .toList();
        InternalReferenceStatus status = issues.isEmpty()
                ? InternalReferenceStatus.COMPLETE
                : InternalReferenceStatus.PARTIAL;
        int entryWeight = 1 + groups.size() + groups.stream()
                .mapToInt(group -> group.representativeReferences().size())
                .sum();
        return new InternalReferenceAnalysis(
                declaration,
                status,
                totalReferenceCount,
                groups,
                issues,
                rawLocations.size(),
                repositoryLocalReferenceCount,
                hitFiles.size(),
                entryWeight);
    }

    private void addLocalReference(
            SemanticReferenceLocation.LocalSource local,
            Set<LocalReference> deduplicated,
            EnumMap<InternalReferenceIssueCode, Integer> issueCounts) {
        if (!validRange(local.range())) {
            increment(issueCounts, InternalReferenceIssueCode.REFERENCE_RANGE_INVALID);
            return;
        }
        deduplicated.add(new LocalReference(local.sourceFile(), syntaxRange(local.range())));
    }

    private Optional<InternalReferenceContext> contextOf(
            RepositorySyntax syntax, LocalReference reference) {
        List<SourceTypeMetadata> containingTypes = syntax.sourceTypes().stream()
                .filter(metadata -> reference.sourceFile().equals(metadata.declaration().identity().sourceFile()))
                .filter(metadata -> contains(metadata.declaration().declarationLocation().range(), reference.range()))
                .sorted(Comparator
                        .comparing((SourceTypeMetadata metadata) -> metadata.declaration().declarationLocation().range().start(),
                                POSITION_ORDER.reversed())
                        .thenComparing(metadata -> metadata.declaration().declarationLocation().range().end(), POSITION_ORDER)
                        .thenComparing(metadata -> metadata.declaration().identity().fullyQualifiedName()))
                .toList();
        if (containingTypes.isEmpty()) {
            return Optional.empty();
        }
        SourceTypeMetadata containingType = containingTypes.getFirst();
        Optional<MethodTarget> method = containingType.members().methods().stream()
                .filter(candidate -> contains(candidate.declarationLocation().range(), reference.range()))
                .filter(candidate -> candidate.analysisTarget().target().isPresent())
                .sorted(Comparator
                        .comparing((SourceMethodMetadata candidate) -> candidate.declarationLocation().range(),
                                Comparator.comparing(SyntaxRange::start, POSITION_ORDER.reversed())
                                        .thenComparing(SyntaxRange::end, POSITION_ORDER))
                        .thenComparing(SourceMethodMetadata::name)
                        .thenComparing(SourceMethodMetadata::paramTypes,
                                InternalSourceReferenceApplicationService::compareStrings))
                .map(candidate -> candidate.analysisTarget().target().orElseThrow())
                .findFirst();
        if (method.isPresent()) {
            return Optional.of(new InternalReferenceContext.Method(method.orElseThrow()));
        }
        return Optional.of(new InternalReferenceContext.Type(containingType.declaration().identity()));
    }

    private List<InternalReferenceGroup> groups(
            Map<InternalReferenceContext, List<InternalReferenceOccurrence>> grouped) {
        List<InternalReferenceGroup> groups = grouped.entrySet().stream()
                .map(entry -> group(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparing(InternalReferenceGroup::context, CONTEXT_ORDER)
                        .thenComparing(group -> group.representativeReferences().getFirst().range(), RANGE_ORDER))
                .toList();
        return List.copyOf(groups);
    }

    private InternalReferenceGroup group(
            InternalReferenceContext context, List<InternalReferenceOccurrence> occurrences) {
        List<InternalReferenceOccurrence> ordered = occurrences.stream()
                .sorted(Comparator.comparing(InternalReferenceOccurrence::range, RANGE_ORDER))
                .toList();
        List<InternalReferenceOccurrence> representatives = ordered.stream()
                .limit(REPRESENTATIVE_LIMIT)
                .toList();
        return new InternalReferenceGroup(
                context,
                representatives,
                new InternalReferenceGroupLimits(
                        REPRESENTATIVE_LIMIT,
                        representatives.size(),
                        ordered.size(),
                        ordered.size() > representatives.size()));
    }

    private static boolean validRange(SemanticRange range) {
        return compare(range.start(), range.end()) <= 0;
    }

    private static boolean contains(SyntaxRange container, SyntaxRange candidate) {
        return POSITION_ORDER.compare(container.start(), candidate.start()) <= 0
                && POSITION_ORDER.compare(container.end(), candidate.end()) >= 0;
    }

    private static int compare(SemanticPosition left, SemanticPosition right) {
        int lineComparison = Integer.compare(left.line(), right.line());
        return lineComparison != 0
                ? lineComparison
                : Integer.compare(left.character(), right.character());
    }

    private static SyntaxRange syntaxRange(SemanticRange range) {
        return new SyntaxRange(syntaxPosition(range.start()), syntaxPosition(range.end()));
    }

    private static SyntaxPosition syntaxPosition(SemanticPosition position) {
        return new SyntaxPosition(position.line(), position.character());
    }

    private static SemanticPosition semanticPosition(SyntaxPosition position) {
        return new SemanticPosition(position.line(), position.character());
    }

    private static void increment(
            EnumMap<InternalReferenceIssueCode, Integer> issueCounts,
            InternalReferenceIssueCode code) {
        issueCounts.merge(code, 1, Integer::sum);
    }

    private static String ownerName(InternalReferenceContext context) {
        return switch (context) {
            case InternalReferenceContext.Type ignored -> "";
            case InternalReferenceContext.Method method -> method.method().fullyQualifiedClassName();
        };
    }

    private static String methodName(InternalReferenceContext context) {
        return switch (context) {
            case InternalReferenceContext.Type ignored -> "";
            case InternalReferenceContext.Method method -> method.method().methodName();
        };
    }

    private static List<String> parameterTypes(InternalReferenceContext context) {
        return switch (context) {
            case InternalReferenceContext.Type ignored -> List.of();
            case InternalReferenceContext.Method method -> method.method().parameterTypes();
        };
    }

    private static int compareStrings(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record LocalReference(String sourceFile, SyntaxRange range) {

        private LocalReference {
            Objects.requireNonNull(sourceFile, "sourceFile is required");
            Objects.requireNonNull(range, "range is required");
        }
    }
}
