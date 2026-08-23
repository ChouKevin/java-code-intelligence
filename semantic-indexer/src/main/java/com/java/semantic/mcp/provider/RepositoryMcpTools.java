package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.repository.RepositoryMcpDtos;
import com.java.semantic.mcp.mapper.RepositoryMcpMapper;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.EntryPointDiscoveryApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 發布 repository catalog 與 entry point MCP 查詢 */
@Component
public final class RepositoryMcpTools implements McpQueryProvider {

    private final RepositoryApplicationService repositoryApplicationService;
    private final EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService;
    private final RepositoryMcpMapper mapper;

    public RepositoryMcpTools(
            RepositoryApplicationService repositoryApplicationService,
            EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService,
            RepositoryMcpMapper mapper) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.entryPointDiscoveryApplicationService = Objects.requireNonNull(
                entryPointDiscoveryApplicationService, "entryPointDiscoveryApplicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_list_repositories",
                        "List repository catalog entries and their current readiness",
                        RepositoryMcpDtos.ListInput.class,
                        RepositoryMcpDtos.ListOutput.class,
                        this::list),
                new McpQueryRegistration<>(
                        "semantic_get_repository",
                        "Get one repository catalog entry and its current revision",
                        RepositoryMcpDtos.GetInput.class,
                        RepositoryMcpDtos.GetOutput.class,
                        this::get),
                new McpQueryRegistration<>(
                        "semantic_list_entry_points",
                        "List revision-pinned framework entry points for one repository",
                        RepositoryMcpDtos.EntryPointsInput.class,
                        RepositoryMcpDtos.EntryPointsOutput.class,
                        this::entryPoints));
    }

    private RepositoryMcpDtos.ListOutput list(RepositoryMcpDtos.ListInput ignored) {
        return mapper.list(repositoryApplicationService.list());
    }

    private RepositoryMcpDtos.GetOutput get(RepositoryMcpDtos.GetInput input) {
        return mapper.get(repositoryApplicationService.status(RepositoryId.of(input.repoId())));
    }

    private RepositoryMcpDtos.EntryPointsOutput entryPoints(RepositoryMcpDtos.EntryPointsInput input) {
        return mapper.entryPoints(entryPointDiscoveryApplicationService.list(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.types()));
    }
}
