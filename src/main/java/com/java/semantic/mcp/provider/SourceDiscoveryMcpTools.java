package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.source.SourceDiscoveryMcpDtos;
import com.java.semantic.mcp.mapper.SourceDiscoveryMcpMapper;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.InternalSourceReferenceApplicationService;
import com.java.semantic.semantic.application.InternalSourceReferenceQuery;
import com.java.semantic.syntax.application.EvidenceSourceApplicationService;
import com.java.semantic.syntax.application.EvidenceSourceQuery;
import com.java.semantic.syntax.application.MethodSourceApplicationService;
import com.java.semantic.syntax.application.MethodSourceQuery;
import com.java.semantic.syntax.application.SourceSegmentApplicationService;
import com.java.semantic.syntax.application.SourceSegmentQuery;
import com.java.semantic.syntax.application.SourceSymbolResolutionApplicationService;
import com.java.semantic.syntax.application.SourceSymbolResolutionQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 發布 source identity、reference 與 bounded source MCP 查詢 */
@Component
public final class SourceDiscoveryMcpTools implements McpQueryProvider {

    private final SourceSymbolResolutionApplicationService sourceSymbolResolutionApplicationService;
    private final InternalSourceReferenceApplicationService internalSourceReferenceApplicationService;
    private final SourceSegmentApplicationService sourceSegmentApplicationService;
    private final MethodSourceApplicationService methodSourceApplicationService;
    private final EvidenceSourceApplicationService evidenceSourceApplicationService;
    private final SourceDiscoveryMcpMapper mapper;

    public SourceDiscoveryMcpTools(
            SourceSymbolResolutionApplicationService sourceSymbolResolutionApplicationService,
            InternalSourceReferenceApplicationService internalSourceReferenceApplicationService,
            SourceSegmentApplicationService sourceSegmentApplicationService,
            MethodSourceApplicationService methodSourceApplicationService,
            EvidenceSourceApplicationService evidenceSourceApplicationService,
            SourceDiscoveryMcpMapper mapper) {
        this.sourceSymbolResolutionApplicationService = Objects.requireNonNull(
                sourceSymbolResolutionApplicationService, "sourceSymbolResolutionApplicationService is required");
        this.internalSourceReferenceApplicationService = Objects.requireNonNull(
                internalSourceReferenceApplicationService, "internalSourceReferenceApplicationService is required");
        this.sourceSegmentApplicationService = Objects.requireNonNull(
                sourceSegmentApplicationService, "sourceSegmentApplicationService is required");
        this.methodSourceApplicationService = Objects.requireNonNull(
                methodSourceApplicationService, "methodSourceApplicationService is required");
        this.evidenceSourceApplicationService = Objects.requireNonNull(
                evidenceSourceApplicationService, "evidenceSourceApplicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_resolve_source_symbol",
                        "Resolve one source symbol in a revision-pinned source context",
                        SourceDiscoveryMcpDtos.ResolveSymbolInput.class,
                        SourceDiscoveryMcpDtos.ResolveSymbolOutput.class,
                        this::resolveSymbol),
                new McpQueryRegistration<>(
                        "semantic_find_internal_references",
                        "Find a deterministic page of internal references for one exact declaration",
                        SourceDiscoveryMcpDtos.InternalReferencesInput.class,
                        SourceDiscoveryMcpDtos.InternalReferencesOutput.class,
                        this::internalReferences),
                new McpQueryRegistration<>(
                        "semantic_get_source_segment",
                        "Read one bounded revision-pinned source segment",
                        SourceDiscoveryMcpDtos.SourceSegmentInput.class,
                        SourceDiscoveryMcpDtos.SourceSegmentOutput.class,
                        this::sourceSegment),
                new McpQueryRegistration<>(
                        "semantic_get_method_source",
                        "Read the canonical declaration source for one method target",
                        SourceDiscoveryMcpDtos.MethodSourceInput.class,
                        SourceDiscoveryMcpDtos.MethodSourceOutput.class,
                        this::methodSource),
                new McpQueryRegistration<>(
                        "semantic_get_evidence_source",
                        "Read a bounded source segment for one typed mapper evidence identity",
                        SourceDiscoveryMcpDtos.EvidenceSourceInput.class,
                        SourceDiscoveryMcpDtos.EvidenceSourceOutput.class,
                        this::evidenceSource));
    }

    private SourceDiscoveryMcpDtos.ResolveSymbolOutput resolveSymbol(
            SourceDiscoveryMcpDtos.ResolveSymbolInput input) {
        return mapper.symbol(sourceSymbolResolutionApplicationService.resolve(new SourceSymbolResolutionQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.context(),
                input.symbol(),
                input.position())));
    }

    private SourceDiscoveryMcpDtos.InternalReferencesOutput internalReferences(
            SourceDiscoveryMcpDtos.InternalReferencesInput input) {
        return mapper.references(internalSourceReferenceApplicationService.find(new InternalSourceReferenceQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.target(),
                input.offset(),
                input.limit())));
    }

    private SourceDiscoveryMcpDtos.SourceSegmentOutput sourceSegment(SourceDiscoveryMcpDtos.SourceSegmentInput input) {
        return mapper.segment(sourceSegmentApplicationService.read(new SourceSegmentQuery(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.location(),
                input.contextLines())));
    }

    private SourceDiscoveryMcpDtos.MethodSourceOutput methodSource(SourceDiscoveryMcpDtos.MethodSourceInput input) {
        return mapper.methodSource(methodSourceApplicationService.read(new MethodSourceQuery(
                RepositoryId.of(input.repoId()), new RepositoryRevision(input.expectedRevision()), input.target())));
    }

    private SourceDiscoveryMcpDtos.EvidenceSourceOutput evidenceSource(
            SourceDiscoveryMcpDtos.EvidenceSourceInput input) {
        EvidenceSourceQuery query = new EvidenceSourceQuery(
                RepositoryId.of(input.repoId()), new RepositoryRevision(input.expectedRevision()), input.identity());
        return mapper.evidence(evidenceSourceApplicationService.read(query));
    }
}
