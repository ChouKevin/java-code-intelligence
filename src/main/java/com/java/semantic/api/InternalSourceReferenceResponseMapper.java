package com.java.semantic.api;

import com.java.semantic.api.dto.BoundedResultResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.InternalSourceReferenceContextPayload;
import com.java.semantic.api.dto.InternalSourceReferenceResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.IssueSummaryResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.ReferenceGroupResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.RepresentativeReferenceResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse.TargetDeclarationResponse;
import com.java.semantic.api.dto.PageResponse;
import com.java.semantic.api.dto.UnavailableDiscoveryFollowUpResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.InternalReferenceContext;
import com.java.semantic.semantic.application.InternalReferenceGroup;
import com.java.semantic.semantic.application.InternalReferenceOccurrence;
import com.java.semantic.semantic.application.InternalSourceReferenceQuery;
import com.java.semantic.semantic.application.InternalSourceReferenceResult;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 將完整內部 reference 分析投影為可獨立續查的 HTTP 回應 */
@Component
public final class InternalSourceReferenceResponseMapper {

    private static final int SOURCE_CONTEXT_LINES = 3;

    private final ExactSourceDeclarationTargetHttpMapper targetMapper;
    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    public InternalSourceReferenceResponseMapper(
            ExactSourceDeclarationTargetHttpMapper targetMapper,
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.targetMapper = Objects.requireNonNull(targetMapper, "targetMapper is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    /** 投影 exact declaration、完整 counts、group page 與 executable follow-ups */
    public InternalSourceReferenceResponse toResponse(
            InternalSourceReferenceQuery query,
            InternalSourceReferenceResult result) {
        InternalSourceReferenceQuery request = Objects.requireNonNull(query, "query is required");
        InternalSourceReferenceResult analysis = Objects.requireNonNull(result, "result is required");
        ExactSourceDeclaration declaration = analysis.targetDeclaration();
        enforceMethodScopedDeclarationRange(declaration);
        return new InternalSourceReferenceResponse(
                analysis.repositoryId().value(),
                analysis.analyzedRevision().value(),
                analysis.status().name(),
                new TargetDeclarationResponse(
                        targetMapper.toPayload(declaration.target()),
                        sourceLocationMapper.toTextRange(declaration.declarationRange()),
                        List.of(sourceFollowUp(
                                analysis.repositoryId(),
                                analysis.analyzedRevision(),
                                new SourceRange(declaration.target().sourceFile(), declaration.declarationRange())))),
                analysis.totalReferenceCount(),
                analysis.groups().stream()
                        .map(group -> group(analysis.repositoryId(), analysis.analyzedRevision(), group))
                        .toList(),
                new PageResponse(
                        analysis.page().offset(),
                        analysis.page().limit(),
                        analysis.page().returnedCount(),
                        analysis.page().totalCount(),
                        analysis.page().hasMore()),
                analysis.issueSummaries().stream()
                        .map(issue -> new IssueSummaryResponse(issue.code().name(), issue.count()))
                        .toList(),
                pageFollowUps(request, analysis));
    }

    private ReferenceGroupResponse group(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            InternalReferenceGroup group) {
        String sourceFile = group.context().sourceFile();
        return new ReferenceGroupResponse(
                context(group.context()),
                group.representativeReferences().stream()
                        .map(reference -> representative(repositoryId, revision, sourceFile, reference))
                        .toList(),
                new BoundedResultResponse(
                        group.limits().limit(),
                        group.limits().returnedCount(),
                        group.limits().totalCount(),
                        group.limits().truncated()),
                contextFollowUps(repositoryId, revision, group.context()),
                List.<UnavailableDiscoveryFollowUpResponse>of());
    }

    private RepresentativeReferenceResponse representative(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            String sourceFile,
            InternalReferenceOccurrence occurrence) {
        SourceRange sourceRange = new SourceRange(sourceFile, occurrence.range());
        return new RepresentativeReferenceResponse(
                sourceLocationMapper.toTextRange(occurrence.range()),
                List.of(sourceFollowUp(repositoryId, revision, sourceRange)));
    }

    private InternalSourceReferenceContextPayload context(InternalReferenceContext context) {
        return switch (context) {
            case InternalReferenceContext.Type type -> new InternalSourceReferenceContextPayload.Type(
                    "TYPE", JavaSourceIdentityHttpMapper.toPayload(type.sourceType()));
            case InternalReferenceContext.Method method -> new InternalSourceReferenceContextPayload.Method(
                    "METHOD", JavaSourceIdentityHttpMapper.toPayload(method.method()));
        };
    }

    private List<DiscoveryFollowUpResponse> contextFollowUps(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            InternalReferenceContext context) {
        List<DiscoveryFollowUp> followUps = switch (context) {
            case InternalReferenceContext.Type type -> followUpFactory.forSourceType(
                    repositoryId, revision, type.sourceType());
            case InternalReferenceContext.Method method -> followUpFactory.forMethod(
                            repositoryId, revision, method.method()).stream()
                    .filter(followUp -> followUp.operation() == DiscoveryFollowUp.Operation.GET_METHOD_SOURCE
                            || followUp.operation() == DiscoveryFollowUp.Operation.ANALYZE_OUTGOING_CALL_GRAPH)
                    .toList();
        };
        return followUps.stream().map(followUpMapper::followUp).toList();
    }

    private DiscoveryFollowUpResponse sourceFollowUp(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceRange sourceRange) {
        return followUpMapper.followUp(followUpFactory.forSourceSegment(
                repositoryId, revision, sourceRange, SOURCE_CONTEXT_LINES));
    }

    private List<DiscoveryFollowUpResponse> pageFollowUps(
            InternalSourceReferenceQuery query,
            InternalSourceReferenceResult result) {
        if (!result.page().hasMore()) {
            return List.of();
        }
        int nextOffset = result.page().offset() + result.page().returnedCount();
        DiscoveryFollowUp followUp = followUpFactory.nextInternalReferencePage(
                result.repositoryId(),
                result.analyzedRevision(),
                query.target(),
                nextOffset,
                result.page().limit());
        return List.of(followUpMapper.followUp(followUp));
    }

    private void enforceMethodScopedDeclarationRange(ExactSourceDeclaration declaration) {
        if (declaration.target() instanceof ExactSourceDeclarationTarget.Member member
                && member.identity() instanceof SourceMemberIdentity.MethodScoped methodScoped
                && !methodScoped.declarationRange().equals(declaration.declarationRange())) {
            throw new IllegalArgumentException("method-scoped member declaration range must not diverge");
        }
    }
}
