package com.java.semantic.trie;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 以原子方式發布不可變的儲存庫版本路由索引 */
@Component
public class ApiTrieService {

    private static final Logger log = LoggerFactory.getLogger(ApiTrieService.class);
    private static final Comparator<ApiEntryPointRef> CANDIDATE_COMPARATOR = Comparator
            .comparing(ApiEntryPointRef::repoId)
            .thenComparing(ApiEntryPointRef::analyzedRevision)
            .thenComparing(ApiEntryPointRef::httpMethod)
            .thenComparing(ApiEntryPointRef::routeTemplate)
            .thenComparing(ref -> ref.sourceType().sourceFile())
            .thenComparing(ref -> ref.sourceType().javaType().fullyQualifiedName())
            .thenComparing(ApiEntryPointRef::methodName)
            .thenComparing(ApiEntryPointRef::analysisTarget, ApiTrieService::compareAnalysisTargets);
    private static final Comparator<MethodTarget> METHOD_TARGET_COMPARATOR = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, ApiTrieService::compareStringsLexicographically);

    private final AtomicReference<TrieState> current = new AtomicReference<>(TrieState.empty());

    public void reload(RepositorySnapshot snapshot, RepositorySyntax syntax) {
        Objects.requireNonNull(syntax, "syntax is required");
        reload(snapshot, () -> syntax);
    }

    public synchronized void reload(
            RepositorySnapshot snapshot,
            Supplier<RepositorySyntax> syntaxSupplier) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        Objects.requireNonNull(syntaxSupplier, "syntaxSupplier is required");
        RepositoryId repositoryId = snapshot.repositoryId();
        TrieState previous = current.get();
        Map<RepositoryId, PublishedRoutes> available = withoutRepository(
                previous.routesByRepository(), repositoryId);
        TrieState cleared = buildState(available);
        current.set(cleared);
        try {
            RepositorySyntax syntax = Objects.requireNonNull(syntaxSupplier.get(), "syntax is required");
            Map<RepositoryId, PublishedRoutes> rebuilt = new HashMap<>(available);
            rebuilt.put(repositoryId, new PublishedRoutes(snapshot.revision(), extractRefs(snapshot, syntax)));
            current.set(buildState(rebuilt));
        } catch (RuntimeException exception) {
            current.set(previous);
            throw exception;
        }
    }

    public synchronized void clear(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        TrieState snapshot = current.get();
        current.set(buildState(withoutRepository(snapshot.routesByRepository(), repositoryId)));
    }

    public ApiRouteMatchBatch lookupMatches(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            String apiPath,
            String httpMethod) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        TrieState state = current.get();
        requirePublishedRoutes(state, repositoryId, expectedRevision);
        NormalizedApiPath normalized = ApiPathNormalizer.normalize(apiPath, httpMethod);
        List<ApiRouteMatch> matches = search(
                state.root(),
                splitPath(normalized.path()),
                0,
                normalized.httpMethod(),
                repositoryId.value()).stream()
                .map(ref -> new ApiRouteMatch(
                        ref,
                        ApiRouteCandidateMatcher.lookupReasons(normalized.path(), normalized.httpMethod(), ref)))
                .toList();
        return new ApiRouteMatchBatch(matches, List.of());
    }

    public ApiRouteMatchBatch suggestMatches(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            String apiPath,
            String httpMethod,
            int limit) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        TrieState state = current.get();
        PublishedRoutes publishedRoutes = requirePublishedRoutes(state, repositoryId, expectedRevision);
        NormalizedApiPath normalized = ApiPathNormalizer.normalize(apiPath, httpMethod);
        List<ApiRouteMatch> bounded = publishedRoutes.entries().stream()
                .filter(ref -> matchesSuggestionMethod(ref, normalized.httpMethod()))
                .map(ref -> new ApiRouteMatch(
                        ref,
                        ApiRouteCandidateMatcher.suggestionReasons(normalized.path(), ref.routeTemplate())))
                .filter(match -> match.matchReasons().contains(ApiRouteMatchReason.SHARED_STATIC_SEGMENT))
                .sorted(Comparator.comparing(ApiRouteMatch::ref, CANDIDATE_COMPARATOR))
                .limit(limit + 1L)
                .toList();
        boolean truncated = bounded.size() > limit;
        List<ApiRouteMatch> matches = bounded.stream().limit(limit).toList();
        List<ApiRouteObservation> observations = truncated
                ? List.of(new ApiRouteObservation(
                        ApiRouteObservationCode.TRUNCATED_CANDIDATES,
                        "route candidates were truncated by the requested limit"))
                : List.of();
        return new ApiRouteMatchBatch(matches, observations);
    }

    private List<ApiEntryPointRef> extractRefs(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax) {
        String repoId = snapshot.repositoryId().value();
        List<ApiEntryPointRef> refs = new ArrayList<>();
        for (EntryPointClass entryPointClass : syntax.entryPoints()) {
            for (EntryPointMethod method : entryPointClass.methods()) {
                if (method instanceof ApiEntryPoint api) {
                    for (String httpMethod : api.httpMethods()) {
                        NormalizedApiPath normalized = ApiPathNormalizer.normalize(api.apiUrl(), httpMethod);
                        ApiEntryPointRef ref = new ApiEntryPointRef(
                                repoId,
                                snapshot.revision().value(),
                                entryPointClass.sourceType(),
                                api.name(),
                                normalized.httpMethod(),
                                normalized.path(),
                                api.analysisTarget());
                        if (hasNonterminalRestWildcard(ref.routeTemplate())) {
                            log.warn(
                                    "API route omitted repoId={} category={}",
                                    repoId,
                                    "NONTERMINAL_REST_WILDCARD");
                        } else {
                            refs.add(ref);
                        }
                    }
                }
            }
        }
        return collisionSurvivors(refs);
    }

    private List<ApiEntryPointRef> collisionSurvivors(List<ApiEntryPointRef> refs) {
        Map<RouteKey, ApiEntryPointRef> survivors = new LinkedHashMap<>();
        for (ApiEntryPointRef candidate : refs) {
            RouteKey key = new RouteKey(candidate.repoId(), candidate.httpMethod(), candidate.routeTemplate());
            survivors.merge(key, candidate, this::chooseCollisionWinner);
        }
        return survivors.values().stream().sorted(CANDIDATE_COMPARATOR).toList();
    }

    private ApiEntryPointRef chooseCollisionWinner(ApiEntryPointRef existing, ApiEntryPointRef candidate) {
        ApiEntryPointRef winner = CANDIDATE_COMPARATOR.compare(existing, candidate) <= 0
                ? existing
                : candidate;
        log.warn(
                "API route collision repoId={} category={}",
                existing.repoId(),
                "INTRA_REPOSITORY_COLLISION");
        return winner;
    }

    private TrieState buildState(Map<RepositoryId, PublishedRoutes> routesByRepository) {
        Map<RepositoryId, PublishedRoutes> copied = new HashMap<>();
        ApiTrieNode root = new ApiTrieNode();
        routesByRepository.forEach((repositoryId, publishedRoutes) -> {
            PublishedRoutes immutableRoutes = new PublishedRoutes(
                    publishedRoutes.revision(), publishedRoutes.entries());
            copied.put(repositoryId, immutableRoutes);
            immutableRoutes.entries().forEach(ref -> insert(root, ref));
        });
        return new TrieState(root, Map.copyOf(copied));
    }

    private void insert(ApiTrieNode root, ApiEntryPointRef ref) {
        ApiTrieNode node = root;
        for (String segment : splitPath(ref.routeTemplate())) {
            node = node.children.computeIfAbsent(segment, ignored -> new ApiTrieNode());
        }
        Map<String, ApiEntryPointRef> refsByRepo = node.methodMap.computeIfAbsent(
                ref.httpMethod(), ignored -> new HashMap<>());
        refsByRepo.put(ref.repoId(), ref);
        if (refsByRepo.size() > 1) {
            log.warn(
                    "API route collision category={}",
                    "CROSS_REPOSITORY_COLLISION");
        }
    }

    private List<ApiEntryPointRef> search(
            ApiTrieNode node,
            String[] segments,
            int index,
            String httpMethod,
            String repositoryId) {
        if (index == segments.length) {
            List<ApiEntryPointRef> direct = resolveRefs(node, httpMethod, repositoryId);
            if (!CollectionUtils.isEmpty(direct)) {
                return direct;
            }
            ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
            return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repositoryId) : List.of();
        }
        ApiTrieNode exact = node.children.get(segments[index]);
        if (Objects.nonNull(exact)) {
            List<ApiEntryPointRef> exactResult = search(
                    exact, segments, index + 1, httpMethod, repositoryId);
            if (!CollectionUtils.isEmpty(exactResult)) {
                return exactResult;
            }
        }
        ApiTrieNode wildcard = node.children.get(ApiTrieNode.WILDCARD);
        if (Objects.nonNull(wildcard) && wildcard != exact) {
            List<ApiEntryPointRef> wildcardResult = search(
                    wildcard, segments, index + 1, httpMethod, repositoryId);
            if (!CollectionUtils.isEmpty(wildcardResult)) {
                return wildcardResult;
            }
        }
        ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
        return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repositoryId) : List.of();
    }

    private List<ApiEntryPointRef> resolveRefs(
            ApiTrieNode node,
            String httpMethod,
            String repositoryId) {
        if (StringUtils.hasText(httpMethod)) {
            List<ApiEntryPointRef> exact = sortedRefs(node.methodMap.get(httpMethod), repositoryId);
            if (!CollectionUtils.isEmpty(exact)) {
                return exact;
            }
            return sortedRefs(node.methodMap.get(ApiTrieNode.METHOD_ALL), repositoryId);
        }
        return node.methodMap.values().stream()
                .flatMap(refs -> refs.values().stream())
                .filter(ref -> matchesScope(ref, repositoryId))
                .sorted(CANDIDATE_COMPARATOR)
                .toList();
    }

    private List<ApiEntryPointRef> sortedRefs(
            Map<String, ApiEntryPointRef> refsByRepo,
            String repositoryId) {
        if (CollectionUtils.isEmpty(refsByRepo)) {
            return List.of();
        }
        return refsByRepo.values().stream()
                .filter(ref -> matchesScope(ref, repositoryId))
                .sorted(CANDIDATE_COMPARATOR)
                .toList();
    }

    private boolean matchesScope(ApiEntryPointRef ref, String repositoryId) {
        return repositoryId.equals(ref.repoId());
    }

    private boolean matchesSuggestionMethod(ApiEntryPointRef ref, String httpMethod) {
        return !StringUtils.hasText(httpMethod)
                || httpMethod.equals(ref.httpMethod())
                || ApiTrieNode.METHOD_ALL.equals(ref.httpMethod());
    }

    private PublishedRoutes requirePublishedRoutes(
            TrieState state,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision) {
        PublishedRoutes publishedRoutes = state.routesByRepository().get(repositoryId);
        if (Objects.isNull(publishedRoutes) || !expectedRevision.equals(publishedRoutes.revision())) {
            throw new ApiRouteIndexNotReadyException(repositoryId, expectedRevision);
        }
        return publishedRoutes;
    }

    private static Map<RepositoryId, PublishedRoutes> withoutRepository(
            Map<RepositoryId, PublishedRoutes> source,
            RepositoryId repositoryId) {
        Map<RepositoryId, PublishedRoutes> result = new HashMap<>(source);
        result.remove(repositoryId);
        return result;
    }

    private static String[] splitPath(String path) {
        return Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }

    private static boolean hasNonterminalRestWildcard(String routeTemplate) {
        String[] segments = splitPath(routeTemplate);
        for (int index = 0; index < segments.length - 1; index++) {
            if (ApiTrieNode.REST_WILDCARD.equals(segments[index])) {
                return true;
            }
        }
        return false;
    }

    private static int compareAnalysisTargets(
            MethodTargetResolution left,
            MethodTargetResolution right) {
        int statusComparison = left.status().name().compareTo(right.status().name());
        if (statusComparison != 0) {
            return statusComparison;
        }
        int targetComparison = compareOptionalTargets(left, right);
        if (targetComparison != 0) {
            return targetComparison;
        }
        int candidateComparison = compareTargetCollections(left.candidates(), right.candidates());
        if (candidateComparison != 0) {
            return candidateComparison;
        }
        return left.reasonCode().compareTo(right.reasonCode());
    }

    private static int compareOptionalTargets(
            MethodTargetResolution left,
            MethodTargetResolution right) {
        if (left.target().isPresent() && right.target().isPresent()) {
            return METHOD_TARGET_COMPARATOR.compare(left.target().orElseThrow(), right.target().orElseThrow());
        }
        return Boolean.compare(left.target().isPresent(), right.target().isPresent());
    }

    private static int compareTargetCollections(List<MethodTarget> left, List<MethodTarget> right) {
        List<MethodTarget> sortedLeft = left.stream().sorted(METHOD_TARGET_COMPARATOR).toList();
        List<MethodTarget> sortedRight = right.stream().sorted(METHOD_TARGET_COMPARATOR).toList();
        int commonSize = Math.min(sortedLeft.size(), sortedRight.size());
        for (int index = 0; index < commonSize; index++) {
            int targetComparison = METHOD_TARGET_COMPARATOR.compare(sortedLeft.get(index), sortedRight.get(index));
            if (targetComparison != 0) {
                return targetComparison;
            }
        }
        return Integer.compare(sortedLeft.size(), sortedRight.size());
    }

    private static int compareStringsLexicographically(List<String> left, List<String> right) {
        int commonSize = Math.min(left.size(), right.size());
        for (int index = 0; index < commonSize; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record RouteKey(String repoId, String httpMethod, String routeTemplate) {
    }

    private record PublishedRoutes(RepositoryRevision revision, List<ApiEntryPointRef> entries) {

        private PublishedRoutes {
            Objects.requireNonNull(revision, "revision is required");
            entries = List.copyOf(entries);
        }
    }

    private record TrieState(ApiTrieNode root, Map<RepositoryId, PublishedRoutes> routesByRepository) {

        private static TrieState empty() {
            return new TrieState(new ApiTrieNode(), Map.of());
        }
    }
}
