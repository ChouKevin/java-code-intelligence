package com.java.semantic.api;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.dto.SourceSegmentRequest;
import com.java.semantic.api.dto.SourceSegmentPayload;
import com.java.semantic.api.dto.SourceSegmentResponse;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.SourceSegmentApplicationService;
import com.java.semantic.syntax.application.SourceSegmentQuery;
import com.java.semantic.syntax.application.SourceSegmentResult;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 提供 repository-contained 且受 byte bound 限制的 canonical source segment */
@RestController
public final class SourceSegmentController {

    private final SourceSegmentApplicationService applicationService;
    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public SourceSegmentController(
            SourceSegmentApplicationService applicationService,
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    /** 讀取 exact range 與可容納的 bounded context */
    @PostMapping("/v1/discovery/source-segment")
    public SourceSegmentResponse getSourceSegment(
            @Valid @RequestBody SourceSegmentRequest request) {
        SourceSegmentQuery query = new SourceSegmentQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                sourceLocationMapper.toSourceRange(request.location()),
                request.contextLines());
        SourceSegmentResult result = applicationService.read(query);
        return new SourceSegmentResponse(
                result.repositoryId().value(),
                result.analyzedRevision().value(),
                new SourceSegmentPayload(
                        sourceLocationMapper.toSourceRange(result.location()),
                        result.content(),
                        result.nextLocation().map(sourceLocationMapper::toSourceRange)),
                result.contextTruncated(),
                result.nextLocation().map(next -> followUpMapper.followUp(
                        followUpFactory.forSourceSegment(
                                result.repositoryId(), result.analyzedRevision(), next, 0)))
                        .stream().toList());
    }
}
