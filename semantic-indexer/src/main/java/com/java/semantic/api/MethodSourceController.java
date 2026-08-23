package com.java.semantic.api;

import com.java.semantic.api.dto.GetMethodSourceRequest;
import com.java.semantic.api.dto.SourceSegmentPayload;
import com.java.semantic.api.dto.MethodSourceResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.MethodSourceApplicationService;
import com.java.semantic.syntax.application.MethodSourceQuery;
import com.java.semantic.syntax.application.MethodSourceResult;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.stream.Stream;

/** 提供 canonical 方法完整宣告與 bounded Java source continuation 的 HTTP 邊界 */
@RestController
public final class MethodSourceController {

    private final MethodSourceApplicationService applicationService;
    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public MethodSourceController(
            MethodSourceApplicationService applicationService,
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    /** 以五欄 canonical MethodTarget 讀取完整宣告與第一個 bounded source segment */
    @PostMapping("/v1/discovery/method-source")
    public MethodSourceResponse methodSource(@Valid @RequestBody GetMethodSourceRequest request) {
        MethodTarget target = JavaSourceIdentityHttpMapper.toDomain(request.target());
        MethodSourceResult result = applicationService.read(new MethodSourceQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                target));
        return new MethodSourceResponse(
                result.repositoryId().value(),
                result.analyzedRevision().value(),
                sourceLocationMapper.toSourceRange(result.declarationLocation()),
                new SourceSegmentPayload(
                        sourceLocationMapper.toSourceRange(result.segment().location()),
                        result.segment().content(),
                        result.segment().nextLocation().map(sourceLocationMapper::toSourceRange)),
                Stream.concat(
                                followUpFactory.forMethodSource(
                                                result.repositoryId(),
                                                result.analyzedRevision(),
                                                target,
                                                result.segment().nextLocation())
                                        .stream(),
                                result.implementationDiscoveryEligible()
                                        ? Stream.of(followUpFactory.forMethodImplementations(
                                                result.repositoryId(), result.analyzedRevision(), target))
                                        : Stream.empty())
                        .map(followUpMapper::followUp)
                        .toList());
    }
}
