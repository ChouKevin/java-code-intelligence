package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.mapper.CallGraphMcpMapper;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 發布 revision-pinned incoming 與 outgoing call graph MCP 查詢 */
@Component
public final class CallGraphMcpTools implements McpQueryProvider {

    private final SemanticAnalysisApplicationService applicationService;
    private final CallGraphMcpMapper mapper;

    public CallGraphMcpTools(SemanticAnalysisApplicationService applicationService, CallGraphMcpMapper mapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_analyze_outgoing_call_graph",
                        "Analyze the bounded outgoing call graph for one canonical method target",
                        CallGraphMcpDtos.Input.class,
                        CallGraphMcpDtos.OutgoingOutput.class,
                        this::outgoing),
                new McpQueryRegistration<>(
                        "semantic_analyze_incoming_call_graph",
                        "Analyze the bounded incoming call graph for one canonical method target",
                        CallGraphMcpDtos.Input.class,
                        CallGraphMcpDtos.IncomingOutput.class,
                        this::incoming));
    }

    private CallGraphMcpDtos.OutgoingOutput outgoing(CallGraphMcpDtos.Input input) {
        RepositoryId repositoryId = RepositoryId.of(input.repoId());
        return mapper.outgoing(applicationService.analyzeOutgoing(
                repositoryId, new RepositoryRevision(input.expectedRevision()), input.target(), input.depth()));
    }

    private CallGraphMcpDtos.IncomingOutput incoming(CallGraphMcpDtos.Input input) {
        RepositoryId repositoryId = RepositoryId.of(input.repoId());
        return mapper.incoming(applicationService.analyzeIncoming(
                repositoryId, new RepositoryRevision(input.expectedRevision()), input.target(), input.depth()));
    }
}
