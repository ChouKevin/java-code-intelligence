package com.java.semantic.trie;

import com.java.semantic.repository.domain.RepositoryId;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 以原子方式發布不可變重建結果，提供不需鎖定的跨儲存庫路由查詢 */
@Component
public class ApiTrieService {

    private static final Logger log = LoggerFactory.getLogger(ApiTrieService.class);
    private static final Comparator<ApiEntryPointRef> CANDIDATE_COMPARATOR = Comparator
            .comparing(ApiEntryPointRef::repoId)
            .thenComparing(ApiEntryPointRef::analyzedRevision)
            .thenComparing(ApiEntryPointRef::httpMethod)
            .thenComparing(ApiEntryPointRef::routeTemplate)
            .thenComparing(ApiEntryPointRef::packageName)
            .thenComparing(ApiEntryPointRef::className)
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
        String repoId = snapshot.repositoryId().value();
        TrieState previous = current.get();
        Map<String, List<ApiEntryPointRef>> available = withoutRepo(previous.entriesByRepo(), repoId);
        TrieState cleared = buildState(available);
        current.set(cleared);
        try {
            RepositorySyntax syntax = Objects.requireNonNull(syntaxSupplier.get(), "syntax is required");
            Map<String, List<ApiEntryPointRef>> rebuilt = new HashMap<>(available);
            rebuilt.put(repoId, extractRefs(snapshot, syntax));
            current.set(buildState(rebuilt));
        } catch (RuntimeException exception) {
            current.set(previous);
            throw exception;
        }
    }

    public synchronized void clear(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        TrieState snapshot = current.get();
        current.set(buildState(withoutRepo(
                snapshot.entriesByRepo(), repositoryId.value())));
    }

    public List<ApiEntryPointRef> lookupCandidates(
            String apiPath,
            String httpMethod,
            String repoScope) {
        NormalizedApiPath normalized = ApiPathNormalizer.normalize(apiPath, httpMethod);
        TrieState snapshot = current.get();
        return search(
                snapshot.root(),
                splitPath(normalized.path()),
                0,
                normalized.httpMethod(),
                repoScope);
    }

    public Optional<ApiEntryPointRef> lookup(String apiPath, String httpMethod) {
        return lookupCandidates(apiPath, httpMethod, "").stream().findFirst();
    }

    public List<ApiEntryPointRef> suggestCandidates(
            String apiPath,
            String httpMethod,
            String repoScope,
            int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        NormalizedApiPath normalized = ApiPathNormalizer.normalize(apiPath, httpMethod);
        return current.get().entriesByRepo().values().stream()
                .flatMap(List::stream)
                .filter(ref -> matchesScope(ref, repoScope))
                .filter(ref -> matchesSuggestionMethod(ref, normalized.httpMethod()))
                .map(ref -> new ScoredRoute(
                        ref,
                        ApiRouteCandidateMatcher.score(normalized.path(), ref.routeTemplate())))
                .filter(scored -> scored.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredRoute::score).reversed()
                        .thenComparing(ScoredRoute::ref, CANDIDATE_COMPARATOR))
                .limit(limit)
                .map(ScoredRoute::ref)
                .toList();
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
                                entryPointClass.packageName(),
                                entryPointClass.className(),
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

    private TrieState buildState(Map<String, List<ApiEntryPointRef>> entriesByRepo) {
        Map<String, List<ApiEntryPointRef>> copied = new HashMap<>();
        ApiTrieNode root = new ApiTrieNode();
        entriesByRepo.forEach((repoId, refs) -> {
            List<ApiEntryPointRef> immutableRefs = List.copyOf(refs);
            copied.put(repoId, immutableRefs);
            immutableRefs.forEach(ref -> insert(root, ref));
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
            String repoScope) {
        if (index == segments.length) {
            List<ApiEntryPointRef> direct = resolveRefs(node, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(direct)) {
                return direct;
            }
            ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
            return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repoScope) : List.of();
        }
        ApiTrieNode exact = node.children.get(segments[index]);
        if (Objects.nonNull(exact)) {
            List<ApiEntryPointRef> exactResult = search(
                    exact, segments, index + 1, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(exactResult)) {
                return exactResult;
            }
        }
        ApiTrieNode wildcard = node.children.get(ApiTrieNode.WILDCARD);
        if (Objects.nonNull(wildcard) && wildcard != exact) {
            List<ApiEntryPointRef> wildcardResult = search(
                    wildcard, segments, index + 1, httpMethod, repoScope);
            if (!CollectionUtils.isEmpty(wildcardResult)) {
                return wildcardResult;
            }
        }
        ApiTrieNode rest = node.children.get(ApiTrieNode.REST_WILDCARD);
        return Objects.nonNull(rest) ? resolveRefs(rest, httpMethod, repoScope) : List.of();
    }

    private List<ApiEntryPointRef> resolveRefs(
            ApiTrieNode node,
            String httpMethod,
            String repoScope) {
        if (StringUtils.hasText(httpMethod)) {
            List<ApiEntryPointRef> exact = sortedRefs(node.methodMap.get(httpMethod), repoScope);
            if (!CollectionUtils.isEmpty(exact)) {
                return exact;
            }
            return sortedRefs(node.methodMap.get(ApiTrieNode.METHOD_ALL), repoScope);
        }
        return node.methodMap.values().stream()
                .flatMap(refs -> refs.values().stream())
                .filter(ref -> matchesScope(ref, repoScope))
                .sorted(CANDIDATE_COMPARATOR)
                .toList();
    }

    private List<ApiEntryPointRef> sortedRefs(
            Map<String, ApiEntryPointRef> refsByRepo,
            String repoScope) {
        if (CollectionUtils.isEmpty(refsByRepo)) {
            return List.of();
        }
        return refsByRepo.values().stream()
                .filter(ref -> matchesScope(ref, repoScope))
                .sorted(CANDIDATE_COMPARATOR)
                .toList();
    }

    private boolean matchesScope(ApiEntryPointRef ref, String repoScope) {
        return !StringUtils.hasText(repoScope) || repoScope.equals(ref.repoId());
    }

    private boolean matchesSuggestionMethod(ApiEntryPointRef ref, String httpMethod) {
        return !StringUtils.hasText(httpMethod)
                || httpMethod.equals(ref.httpMethod())
                || ApiTrieNode.METHOD_ALL.equals(ref.httpMethod());
    }

    private static Map<String, List<ApiEntryPointRef>> withoutRepo(
            Map<String, List<ApiEntryPointRef>> source,
            String repoId) {
        Map<String, List<ApiEntryPointRef>> result = new HashMap<>(source);
        result.remove(repoId);
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

    private record ScoredRoute(ApiEntryPointRef ref, int score) {
    }

    private record TrieState(ApiTrieNode root, Map<String, List<ApiEntryPointRef>> entriesByRepo) {

        private static TrieState empty() {
            return new TrieState(new ApiTrieNode(), Map.of());
        }
    }
}
