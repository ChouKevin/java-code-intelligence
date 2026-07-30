package com.java.semantic.api;

import com.java.semantic.api.dto.CandidatePageResponse;
import com.java.semantic.api.dto.DiscoverEventListenersResponse;
import com.java.semantic.api.dto.EventListenerCandidateResponse;
import com.java.semantic.api.dto.ListenerAnnotationEvidenceResponse;
import com.java.semantic.api.dto.ListenerObservationSummaryResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.api.dto.PositionResponse;
import com.java.semantic.api.dto.SourceRangeResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.CandidatePage;
import com.java.semantic.syntax.application.EventListenerCandidate;
import com.java.semantic.syntax.application.ListenerAnnotationEvidence;
import com.java.semantic.syntax.application.ListenerObservationSummary;
import com.java.semantic.syntax.application.ListenerSourceLocation;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 將事件監聽器探索結果轉換為穩定 HTTP 回應 */
@Component
public final class EventListenerDiscoveryResponseMapper {

    public DiscoverEventListenersResponse toResponse(RevisionBoundEventListenerDiscovery discovery) {
        Objects.requireNonNull(discovery, "discovery is required");
        CandidatePage candidates = discovery.discovery().candidates();
        return new DiscoverEventListenersResponse(
                discovery.repositoryId().value(),
                discovery.analyzedRevision().value(),
                discovery.requestedEventType(),
                candidates.candidates().stream().map(this::candidate).toList(),
                page(candidates),
                discovery.discovery().observations().stream().map(this::observation).toList());
    }

    private CandidatePageResponse page(CandidatePage page) {
        return new CandidatePageResponse(
                page.offset(),
                page.limit(),
                page.returnedCount(),
                page.totalCount(),
                page.hasMore());
    }

    private EventListenerCandidateResponse candidate(EventListenerCandidate candidate) {
        MethodTargetResponse target = target(candidate.target());
        SourceRangeResponse sourceRange = sourceRange(candidate.sourceLocation());
        Assert.isTrue(target.sourceFile().equals(sourceRange.sourceFile()),
                "candidate target and source range sourceFile must match");
        return new EventListenerCandidateResponse(
                target,
                candidate.annotationEvidence().stream().map(this::annotationEvidence).toList(),
                sourceRange);
    }

    private ListenerAnnotationEvidenceResponse annotationEvidence(ListenerAnnotationEvidence evidence) {
        return new ListenerAnnotationEvidenceResponse(evidence.kind().name(), evidence.matchKind().name());
    }

    private ListenerObservationSummaryResponse observation(ListenerObservationSummary observation) {
        List<SourceRangeResponse> samples =
                observation.sourceLocations().stream().map(this::sourceRange).toList();
        return new ListenerObservationSummaryResponse(
                observation.code().name(),
                observation.totalCount(),
                samples);
    }

    private MethodTargetResponse target(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }

    private SourceRangeResponse sourceRange(ListenerSourceLocation sourceLocation) {
        return new SourceRangeResponse(
                sourceLocation.sourceFile(),
                new PositionResponse(sourceLocation.start().line(), sourceLocation.start().character()),
                new PositionResponse(sourceLocation.end().line(), sourceLocation.end().character()));
    }
}
