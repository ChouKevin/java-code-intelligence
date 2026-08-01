package com.java.semantic.api;

import com.java.semantic.api.dto.DeclaredTypeResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.MethodSourceSymbolCandidateResponse;
import com.java.semantic.api.dto.PositionResponse;
import com.java.semantic.api.dto.SourceContextCandidateLimitsResponse;
import com.java.semantic.api.dto.SourceContextCandidateResponse;
import com.java.semantic.api.dto.SourceMethodContextCandidateResponse;
import com.java.semantic.api.dto.SourceRangeResponse;
import com.java.semantic.api.dto.SourceSymbolCandidateResponse;
import com.java.semantic.api.dto.SourceSymbolIssueSummaryResponse;
import com.java.semantic.api.dto.SourceSymbolResolutionResponse;
import com.java.semantic.api.dto.SourceTypeContextCandidateResponse;
import com.java.semantic.api.dto.SourceTypeSymbolCandidateResponse;
import com.java.semantic.api.dto.StaticConstantSourceSymbolCandidateResponse;
import com.java.semantic.api.dto.VariableLikeSourceSymbolCandidateResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.NavigableSourceContextCandidate;
import com.java.semantic.syntax.application.NavigableSourceSymbolCandidate;
import com.java.semantic.syntax.application.RevisionBoundSourceSymbolResolution;
import com.java.semantic.syntax.application.SourceContextCandidate;
import com.java.semantic.syntax.application.SourceMethodContextCandidate;
import com.java.semantic.syntax.application.SourceRange;
import com.java.semantic.syntax.application.SourceSymbolCandidate;
import com.java.semantic.syntax.application.SourceTypeContextCandidate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** source-symbol application identities 與 evidence 的 closed HTTP projection */
@Component
public final class SourceSymbolResolutionResponseMapper {

    private final StructuredDiscoveryResponseMapper followUpMapper;

    public SourceSymbolResolutionResponseMapper(StructuredDiscoveryResponseMapper followUpMapper) {
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    public SourceSymbolResolutionResponse toResponse(RevisionBoundSourceSymbolResolution result) {
        RevisionBoundSourceSymbolResolution resolution = Objects.requireNonNull(result, "result is required");
        List<SourceSymbolIssueSummaryResponse> issues = resolution.issueSummaries().stream()
                .map(issue -> new SourceSymbolIssueSummaryResponse(issue.code().name(), issue.count()))
                .toList();
        return new SourceSymbolResolutionResponse(
                resolution.repositoryId().value(),
                resolution.analyzedRevision().value(),
                resolution.status().name(),
                resolution.contextCandidates().stream().map(this::contextCandidate).toList(),
                new SourceContextCandidateLimitsResponse(
                        resolution.contextCandidateLimits().candidateLimit(),
                        resolution.contextCandidateLimits().returnedCount(),
                        resolution.contextCandidateLimits().totalCount(),
                        resolution.contextCandidateLimits().truncated()),
                resolution.candidates().stream().map(this::symbolCandidate).toList(),
                issues);
    }

    private SourceContextCandidateResponse contextCandidate(NavigableSourceContextCandidate navigable) {
        SourceContextCandidate candidate = navigable.candidate();
        DiscoveryFollowUp retry = navigable.retry();
        return switch (candidate) {
            case SourceTypeContextCandidate type -> new SourceTypeContextCandidateResponse(
                    type.kind().name(), type.sourceFile(), followUp(retry));
            case SourceMethodContextCandidate method -> new SourceMethodContextCandidateResponse(
                    method.kind().name(), MethodTargetHttpMapper.toResponse(method.target()), followUp(retry));
        };
    }

    private SourceSymbolCandidateResponse symbolCandidate(NavigableSourceSymbolCandidate navigable) {
        SourceSymbolCandidate candidate = navigable.candidate();
        List<DiscoveryFollowUp> availableFollowUps = navigable.availableFollowUps();
        return switch (candidate) {
            case SourceSymbolCandidate.VariableLike variable -> new VariableLikeSourceSymbolCandidateResponse(
                    variable.kind().name(),
                    variable.name(),
                    declarationOwner(variable.identity()),
                    new DeclaredTypeResponse(variable.writtenType(), variable.resolvedType()),
                    range(variable.declarationRange()),
                    range(variable.representativeOccurrence()),
                    variable.occurrenceCount(),
                    followUps(availableFollowUps));
            case SourceSymbolCandidate.StaticConstant constant -> new StaticConstantSourceSymbolCandidateResponse(
                    constant.kind().name(),
                    constant.name(),
                    declarationOwner(constant.identity()),
                    new DeclaredTypeResponse(constant.writtenType(), constant.resolvedType()),
                    constant.initializerSource(),
                    range(constant.declarationRange()),
                    range(constant.representativeOccurrence()),
                    constant.occurrenceCount(),
                    followUps(availableFollowUps));
            case SourceSymbolCandidate.Method method -> new MethodSourceSymbolCandidateResponse(
                    method.kind().name(),
                    method.name(),
                    MethodTargetHttpMapper.toResponse(method.identity()),
                    range(method.declarationRange()),
                    range(method.representativeOccurrence()),
                    method.occurrenceCount(),
                    followUps(availableFollowUps));
            case SourceSymbolCandidate.SourceType type -> new SourceTypeSymbolCandidateResponse(
                    type.kind().name(),
                    type.name(),
                    type.identity().fullyQualifiedName(),
                    type.identity().sourceFile(),
                    range(type.declarationRange()),
                    range(type.representativeOccurrence()),
                    type.occurrenceCount(),
                    followUps(availableFollowUps));
        };
    }

    private String declarationOwner(SourceMemberIdentity identity) {
        return switch (identity) {
            case SourceMemberIdentity.TypeMember member -> member.ownerType().fullyQualifiedName();
            case SourceMemberIdentity.MethodScoped local -> {
                MethodTarget method = local.declaringMethod();
                yield method.packageName().isEmpty()
                        ? method.className()
                        : method.packageName() + "." + method.className();
            }
        };
    }

    private List<DiscoveryFollowUpResponse> followUps(List<DiscoveryFollowUp> followUps) {
        return followUps.stream().map(this::followUp).toList();
    }

    private DiscoveryFollowUpResponse followUp(DiscoveryFollowUp followUp) {
        return followUpMapper.followUp(followUp);
    }

    private SourceRangeResponse range(SourceRange sourceRange) {
        return new SourceRangeResponse(
                sourceRange.sourceFile(),
                new PositionResponse(sourceRange.range().start().line(), sourceRange.range().start().character()),
                new PositionResponse(sourceRange.range().end().line(), sourceRange.range().end().character()));
    }
}
