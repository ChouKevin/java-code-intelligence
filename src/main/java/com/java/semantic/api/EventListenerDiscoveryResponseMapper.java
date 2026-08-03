package com.java.semantic.api;

import com.java.semantic.api.dto.PageResponse;
import com.java.semantic.api.dto.DiscoverEventListenersResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.EventListenerCandidateResponse;
import com.java.semantic.api.dto.ListenerAnnotationEvidenceResponse;
import com.java.semantic.api.dto.ListenerObservationSummaryResponse;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.syntax.application.CandidatePage;
import com.java.semantic.syntax.application.EventListenerCandidate;
import com.java.semantic.syntax.application.ListenerAnnotationEvidence;
import com.java.semantic.syntax.application.ListenerObservationSummary;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.domain.SourceRange;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 將事件監聽器探索結果轉換為穩定 HTTP 回應 */
@Component
public final class EventListenerDiscoveryResponseMapper {

    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public EventListenerDiscoveryResponseMapper(
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    public DiscoverEventListenersResponse toResponse(RevisionBoundEventListenerDiscovery discovery) {
        Objects.requireNonNull(discovery, "discovery is required");
        CandidatePage candidates = discovery.discovery().candidates();
        return new DiscoverEventListenersResponse(
                discovery.repositoryId().value(),
                discovery.analyzedRevision().value(),
                discovery.requestedEventType(),
                candidates.candidates().stream()
                        .map(candidate -> candidate(discovery, candidate))
                        .toList(),
                page(candidates),
                discovery.discovery().observations().stream().map(this::observation).toList(),
                pageFollowUps(discovery, candidates));
    }

    private PageResponse page(CandidatePage page) {
        return new PageResponse(
                page.offset(),
                page.limit(),
                page.returnedCount(),
                page.totalCount(),
                page.hasMore());
    }

    private EventListenerCandidateResponse candidate(
            RevisionBoundEventListenerDiscovery discovery,
            EventListenerCandidate candidate) {
        MethodTargetPayload target = JavaSourceIdentityHttpMapper.toPayload(candidate.target());
        Assert.isTrue(target.sourceType().sourceFile().equals(candidate.declarationRange().sourceFile()),
                "candidate target and source range sourceFile must match");
        TextRangePayload sourceRange = sourceLocationMapper.toTextRange(candidate.declarationRange());
        return new EventListenerCandidateResponse(
                target,
                candidate.annotationEvidence().stream().map(this::annotationEvidence).toList(),
                sourceRange,
                followUpFactory.forMethod(
                                discovery.repositoryId(), discovery.analyzedRevision(), candidate.target())
                        .stream().map(followUpMapper::followUp).toList());
    }

    private ListenerAnnotationEvidenceResponse annotationEvidence(ListenerAnnotationEvidence evidence) {
        return new ListenerAnnotationEvidenceResponse(evidence.kind().name(), evidence.matchKind().name());
    }

    private ListenerObservationSummaryResponse observation(ListenerObservationSummary observation) {
        List<SourceRangePayload> samples = observation.declarationRanges().stream()
                .map(sourceLocationMapper::toSourceRange)
                .toList();
        return new ListenerObservationSummaryResponse(
                observation.code().name(),
                observation.totalCount(),
                samples);
    }

    private List<DiscoveryFollowUpResponse> pageFollowUps(
            RevisionBoundEventListenerDiscovery discovery,
            CandidatePage page) {
        if (!page.hasMore()) {
            return List.of();
        }
        DiscoveryFollowUp followUp = followUpFactory.nextEventListenerPage(
                discovery.repositoryId(),
                discovery.analyzedRevision(),
                discovery.requestedEventType(),
                page.offset() + page.returnedCount(),
                page.limit());
        return List.of(followUpMapper.followUp(followUp));
    }

}
