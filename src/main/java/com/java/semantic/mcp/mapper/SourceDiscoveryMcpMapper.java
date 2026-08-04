package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.source.McpEvidenceIdentityPayload;
import com.java.semantic.mcp.dto.source.McpExactSourceDeclarationTargetPayload;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads;
import com.java.semantic.semantic.application.InternalSourceReferenceResult;
import com.java.semantic.syntax.application.EvidenceSourceResult;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.MethodSourceResult;
import com.java.semantic.syntax.application.RevisionBoundSourceSymbolResolution;
import com.java.semantic.syntax.application.SourceSegmentResult;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 將 source navigation 結果投影為 MCP transport DTO */
@Component
public final class SourceDiscoveryMcpMapper {

    /** 將封閉 MCP target 還原為 exact declaration domain target */
    public ExactSourceDeclarationTarget toDomain(McpExactSourceDeclarationTargetPayload payload) {
        return switch (Objects.requireNonNull(payload, "payload is required")) {
            case McpExactSourceDeclarationTargetPayload.Type type -> new ExactSourceDeclarationTarget.Type(
                    McpJavaIdentityPayloads.toDomain(type.identity()));
            case McpExactSourceDeclarationTargetPayload.Method method -> new ExactSourceDeclarationTarget.Method(
                    McpJavaIdentityPayloads.toDomain(method.identity()));
            case McpExactSourceDeclarationTargetPayload.Member member -> new ExactSourceDeclarationTarget.Member(
                    McpJavaIdentityPayloads.toDomain(member.identity()));
        };
    }

    /** 將封閉 MCP evidence identity 還原為既有 evidence domain identity */
    public EvidenceSourceQuery.EvidenceIdentity toDomain(McpEvidenceIdentityPayload payload) {
        return new EvidenceIdentityMcpMapper().toDomain(payload);
    }

    public SourceDiscoveryMcpDtos.ResolveSymbolOutput symbol(RevisionBoundSourceSymbolResolution result) {
        return new SourceDiscoveryMcpDtos.ResolveSymbolOutput(result);
    }

    public SourceDiscoveryMcpDtos.InternalReferencesOutput references(InternalSourceReferenceResult result) {
        return new SourceDiscoveryMcpDtos.InternalReferencesOutput(result);
    }

    public SourceDiscoveryMcpDtos.SourceSegmentOutput segment(SourceSegmentResult result) {
        SourceSegmentResult source = Objects.requireNonNull(result, "result is required");
        return new SourceDiscoveryMcpDtos.SourceSegmentOutput(
                source.repositoryId().value(),
                source.analyzedRevision().value(),
                source.location(),
                source.content(),
                source.nextLocation(),
                source.contextTruncated(),
                sourceSegmentFollowUps(source.repositoryId().value(), source.analyzedRevision().value(), source.nextLocation()));
    }

    public SourceDiscoveryMcpDtos.MethodSourceOutput methodSource(MethodSourceResult result) {
        MethodSourceResult source = Objects.requireNonNull(result, "result is required");
        return new SourceDiscoveryMcpDtos.MethodSourceOutput(
                source.repositoryId().value(),
                source.analyzedRevision().value(),
                source.declarationLocation(),
                segment(source.segment()),
                source.implementationDiscoveryEligible(),
                sourceSegmentFollowUps(
                        source.repositoryId().value(), source.analyzedRevision().value(), source.segment().nextLocation()));
    }

    public SourceDiscoveryMcpDtos.EvidenceSourceOutput evidence(EvidenceSourceResult result) {
        EvidenceSourceResult source = Objects.requireNonNull(result, "result is required");
        return new SourceDiscoveryMcpDtos.EvidenceSourceOutput(
                source.repositoryId().value(),
                source.analyzedRevision().value(),
                source.identity(),
                source.location(),
                segment(source.segment()),
                sourceSegmentFollowUps(
                        source.repositoryId().value(), source.analyzedRevision().value(), source.segment().nextLocation()));
    }

    private SourceDiscoveryMcpDtos.SourceSegment segment(SourceRangeSegment source) {
        SourceRangeSegment value = Objects.requireNonNull(source, "source is required");
        return new SourceDiscoveryMcpDtos.SourceSegment(
                value.location(), value.content(), value.nextLocation(), value.contextTruncated());
    }

    private List<SourceDiscoveryMcpDtos.SourceSegmentFollowUp> sourceSegmentFollowUps(
            String repoId,
            String revision,
            Optional<SourceRange> nextLocation) {
        return nextLocation.map(location -> List.of(new SourceDiscoveryMcpDtos.SourceSegmentFollowUp(
                "semantic_get_source_segment",
                new SourceDiscoveryMcpDtos.SourceSegmentInput(repoId, revision, location, 0)))).orElseGet(List::of);
    }
}
