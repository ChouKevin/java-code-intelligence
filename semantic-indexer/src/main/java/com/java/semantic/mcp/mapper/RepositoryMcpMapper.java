package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.repository.RepositoryMcpDtos;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.syntax.application.RevisionBoundEntryPoints;
import org.springframework.stereotype.Component;

import java.util.List;

/** 將 repository application 結果投影為 MCP transport DTO */
@Component
public final class RepositoryMcpMapper {

    public RepositoryMcpDtos.ListOutput list(List<RepositoryStatus> repositories) {
        return new RepositoryMcpDtos.ListOutput(List.copyOf(repositories));
    }

    public RepositoryMcpDtos.GetOutput get(RepositoryStatus repository) {
        return new RepositoryMcpDtos.GetOutput(repository);
    }

    public RepositoryMcpDtos.EntryPointsOutput entryPoints(RevisionBoundEntryPoints result) {
        return new RepositoryMcpDtos.EntryPointsOutput(result);
    }
}
