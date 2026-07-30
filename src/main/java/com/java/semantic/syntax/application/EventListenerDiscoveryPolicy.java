package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;

import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 從不可變語法結果辨識直接標註的 Spring 事件監聽器
 *
 * 依賴感知的註解繫結與組合或 meta-annotation 探索尚未支援
 */
public final class EventListenerDiscoveryPolicy {

    private static final List<ListenerAnnotationKind> SUPPORTED_ANNOTATION_KINDS = List.of(
            ListenerAnnotationKind.EVENT_LISTENER,
            ListenerAnnotationKind.TRANSACTIONAL_EVENT_LISTENER);

    private static final Comparator<ListenerAnnotationEvidence> ANNOTATION_EVIDENCE_PROTOCOL_ORDER =
            Comparator.comparingInt(evidence -> evidence.kind().protocolOrder());

    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, EventListenerDiscoveryPolicy::compareParameterTypes);

    private static final Comparator<ListenerSourceLocation> SOURCE_LOCATION_ORDER = Comparator
            .comparing(ListenerSourceLocation::sourceFile)
            .thenComparingInt(location -> location.start().line())
            .thenComparingInt(location -> location.start().character())
            .thenComparingInt(location -> location.end().line())
            .thenComparingInt(location -> location.end().character());

    /**
     * 外部 Spring annotation 通常沒有 binary classpath binding，因此只在 binding 缺席時使用原始寫法退回比對
     * MethodTarget.parameterTypes 是保留陣列與巢狀型別的呼叫圖 identity authority
     */
    public EventListenerDiscoveryPage discover(RepositorySyntax syntax, String eventType, int offset, int limit) {
        Objects.requireNonNull(syntax, "syntax is required");
        Assert.hasText(eventType, "eventType is required");
        List<EventListenerCandidate> candidates = new ArrayList<>();
        List<ListenerSourceLocation> unresolvedLocations = new ArrayList<>();
        for (ClassMetadata metadata : syntax.classes()) {
            for (ClassMetadata.MethodSignature method : metadata.methods()) {
                List<ListenerAnnotationEvidence> annotationEvidence = matchingEvidence(method.annotationEvidence());
                if (CollectionUtils.isEmpty(annotationEvidence)) {
                    continue;
                }
                MethodTargetResolution resolution = method.analysisTarget();
                if (!AnalysisTargetStatus.RESOLVED.equals(resolution.status())) {
                    unresolvedLocations.add(ListenerSourceLocation.from(metadata.sourceFile(), method.range()));
                    continue;
                }
                MethodTarget target = resolution.target().orElseThrow();
                validateTargetSourceFile(metadata.sourceFile(), target.sourceFile());
                if (!target.parameterTypes().contains(eventType)) {
                    continue;
                }
                ListenerSourceLocation sourceLocation = ListenerSourceLocation.from(
                        metadata.sourceFile(), method.range());
                candidates.add(new EventListenerCandidate(target, sourceLocation, annotationEvidence));
            }
        }
        candidates.sort(Comparator.comparing(EventListenerCandidate::target, TARGET_ORDER));
        List<EventListenerCandidate> pageCandidates = page(candidates, offset, limit);
        // hasMore 防止 Agent 把部分 candidate 當成完整 evidence
        CandidatePage candidatePage = new CandidatePage(
                pageCandidates, offset, limit, pageCandidates.size(), candidates.size(),
                hasMore(candidates.size(), offset, limit));
        List<ListenerObservationSummary> observations = observations(unresolvedLocations);
        return new EventListenerDiscoveryPage(candidatePage, observations);
    }

    private static void validateTargetSourceFile(String metadataSourceFile, String targetSourceFile) {
        if (!metadataSourceFile.equals(targetSourceFile)) {
            throw new EventListenerDiscoveryContractException(metadataSourceFile, targetSourceFile);
        }
    }

    private static List<ListenerAnnotationEvidence> matchingEvidence(List<AnnotationEvidence> annotations) {
        Map<ListenerAnnotationKind, ListenerAnnotationEvidence> matches = new HashMap<>();
        for (AnnotationEvidence annotation : annotations) {
            for (ListenerAnnotationKind kind : SUPPORTED_ANNOTATION_KINDS) {
                match(kind, annotation).ifPresent(evidence ->
                        matches.merge(kind, evidence, EventListenerDiscoveryPolicy::preferResolvedIdentity));
            }
        }
        return matches.values().stream().sorted(ANNOTATION_EVIDENCE_PROTOCOL_ORDER).toList();
    }

    private static ListenerAnnotationEvidence preferResolvedIdentity(
            ListenerAnnotationEvidence existing, ListenerAnnotationEvidence candidate) {
        if (AnnotationMatchKind.RESOLVED_IDENTITY.equals(candidate.matchKind())) {
            return candidate;
        }
        return existing;
    }

    private static Optional<ListenerAnnotationEvidence> match(
            ListenerAnnotationKind kind, AnnotationEvidence annotation) {
        Optional<ResolvedTypeIdentity> resolvedType = annotation.resolvedType();
        if (resolvedType.isPresent()) {
            ResolvedTypeIdentity identity = resolvedType.get();
            return kind.matchesResolvedType(identity.packageName(), identity.className())
                    ? Optional.of(new ListenerAnnotationEvidence(kind, AnnotationMatchKind.RESOLVED_IDENTITY))
                    : Optional.empty();
        }
        return kind.matchesWrittenName(annotation.writtenName())
                ? Optional.of(new ListenerAnnotationEvidence(kind, AnnotationMatchKind.WRITTEN_NAME))
                : Optional.empty();
    }

    private static List<EventListenerCandidate> page(
            List<EventListenerCandidate> candidates, int offset, int limit) {
        long start = Math.min((long) offset, candidates.size());
        long end = Math.min(start + (long) limit, candidates.size());
        return List.copyOf(candidates.subList((int) start, (int) end));
    }

    private static boolean hasMore(int totalCount, int offset, int limit) {
        long pageEnd = Math.min((long) offset + (long) limit, totalCount);
        return pageEnd < totalCount;
    }

    private static List<ListenerObservationSummary> observations(List<ListenerSourceLocation> unresolvedLocations) {
        if (CollectionUtils.isEmpty(unresolvedLocations)) {
            return List.of();
        }
        List<ListenerSourceLocation> sorted = unresolvedLocations.stream().sorted(SOURCE_LOCATION_ORDER).toList();
        List<ListenerSourceLocation> samples = sorted.subList(
                0, Math.min(sorted.size(), EventListenerDiscoveryConstraints.OBSERVATION_SAMPLE_LIMIT));
        // AMBIGUOUS 與 UNRESOLVED 都沒有唯一可供呼叫圖使用的 target，因此共用同一診斷原因
        // 診斷 totalCount 保持完整，僅 source location samples 受固定上限限制
        return List.of(new ListenerObservationSummary(
                ListenerObservationCode.LISTENER_TARGET_UNRESOLVED, unresolvedLocations.size(), samples));
    }

    private static int compareParameterTypes(List<String> left, List<String> right) {
        int sharedSize = Math.min(left.size(), right.size());
        for (int index = 0; index < sharedSize; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
