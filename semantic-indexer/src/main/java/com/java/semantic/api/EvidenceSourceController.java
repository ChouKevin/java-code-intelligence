package com.java.semantic.api;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.dto.EvidenceSourceIdentityPayload;
import com.java.semantic.api.dto.EvidenceSourceRequest;
import com.java.semantic.api.dto.EvidenceSourceResponse;
import com.java.semantic.api.dto.SourceSegmentPayload;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.EvidenceSourceApplicationService;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.EvidenceSourceResult;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/** 只以既有 typed evidence identity 回讀 revision-pinned 原始證據 */
@RestController
public final class EvidenceSourceController {

    private final EvidenceSourceApplicationService applicationService;
    private final MapperIdentityHttpMapper mapperIdentityHttpMapper;
    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public EvidenceSourceController(
            EvidenceSourceApplicationService applicationService,
            MapperIdentityHttpMapper mapperIdentityHttpMapper,
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.mapperIdentityHttpMapper = Objects.requireNonNull(
                mapperIdentityHttpMapper, "mapperIdentityHttpMapper is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    /** 讀取 annotation SQL、MyBatis statement 或 MyBatis fragment 的首個 bounded segment */
    @PostMapping("/v1/discovery/evidence-source")
    public EvidenceSourceResponse evidenceSource(@Valid @RequestBody EvidenceSourceRequest request) {
        EvidenceSourceQuery query = new EvidenceSourceQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                toDomain(request.identity()));
        EvidenceSourceResult result = applicationService.read(query);
        return new EvidenceSourceResponse(
                result.repositoryId().value(),
                result.analyzedRevision().value(),
                toPayload(result.identity()),
                sourceLocationMapper.toSourceRange(result.location()),
                new SourceSegmentPayload(
                        sourceLocationMapper.toSourceRange(result.segment().location()),
                        result.segment().content(),
                        result.segment().nextLocation().map(sourceLocationMapper::toSourceRange)),
                result.segment().nextLocation().map(next -> followUpMapper.followUp(
                                followUpFactory.forSourceSegment(
                                        result.repositoryId(), result.analyzedRevision(), next, 0)))
                        .stream().toList());
    }

    private EvidenceSourceQuery.EvidenceIdentity toDomain(EvidenceSourceIdentityPayload identity) {
        return switch (identity.kind()) {
            case "ANNOTATION_SQL" -> new EvidenceSourceQuery.AnnotationSql(
                    mapperIdentityHttpMapper.toDomain(identity.statementIdentity().orElseThrow()));
            case "MAPPER_STATEMENT" -> new EvidenceSourceQuery.MapperStatement(
                    mapperIdentityHttpMapper.toDomain(identity.statementIdentity().orElseThrow()));
            case "MAPPER_FRAGMENT" -> new EvidenceSourceQuery.MapperFragment(
                    mapperIdentityHttpMapper.toDomain(identity.fragmentIdentity().orElseThrow()));
            default -> throw new IllegalArgumentException("unsupported evidence identity kind");
        };
    }

    private EvidenceSourceIdentityPayload toPayload(EvidenceSourceQuery.EvidenceIdentity identity) {
        return switch (identity) {
            case EvidenceSourceQuery.AnnotationSql annotation -> new EvidenceSourceIdentityPayload(
                    "ANNOTATION_SQL", Optional.of(mapperIdentityHttpMapper.toPayload(annotation.identity())), Optional.empty());
            case EvidenceSourceQuery.MapperStatement statement -> new EvidenceSourceIdentityPayload(
                    "MAPPER_STATEMENT", Optional.of(mapperIdentityHttpMapper.toPayload(statement.identity())), Optional.empty());
            case EvidenceSourceQuery.MapperFragment fragment -> new EvidenceSourceIdentityPayload(
                    "MAPPER_FRAGMENT", Optional.empty(), Optional.of(mapperIdentityHttpMapper.toPayload(fragment.identity())));
        };
    }
}
