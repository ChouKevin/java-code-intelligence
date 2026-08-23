package com.java.semantic.api;

import com.java.semantic.api.dto.EntryPointListRequest;
import com.java.semantic.api.dto.EntryPointsResponse;
import com.java.semantic.api.dto.RepositoryStatusResponse;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.EntryPointDiscoveryApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** 儲存庫生命週期的 authenticated HTTP API */
@RestController
@RequestMapping("/v1/repositories")
public class RepositoryController {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RepositoryStatusMapper mapper;
    private final EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService;
    private final EntryPointResponseMapper entryPointResponseMapper;

    public RepositoryController(
            RepositoryApplicationService repositoryApplicationService,
            RepositoryStatusMapper mapper,
            EntryPointDiscoveryApplicationService entryPointDiscoveryApplicationService,
            EntryPointResponseMapper entryPointResponseMapper) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.entryPointDiscoveryApplicationService = Objects.requireNonNull(
                entryPointDiscoveryApplicationService, "entryPointDiscoveryApplicationService is required");
        this.entryPointResponseMapper = Objects.requireNonNull(
                entryPointResponseMapper, "entryPointResponseMapper is required");
    }

    @GetMapping
    public List<RepositoryStatusResponse> list() {
        return repositoryApplicationService.list().stream()
                .map(mapper::toResponse)
                .toList();
    }

    @GetMapping("/{repoId}")
    public RepositoryStatusResponse status(@PathVariable String repoId) {
        return mapper.toResponse(repositoryApplicationService.status(RepositoryId.of(repoId)));
    }

    @GetMapping("/{repoId}/entry-points")
    public EntryPointsResponse entryPoints(
            @PathVariable String repoId,
            @RequestParam String expectedRevision,
            @RequestParam(required = false) String types) {
        RepositoryId repositoryId = RepositoryId.of(repoId);
        RepositoryRevision revision = new RepositoryRevision(expectedRevision);
        EntryPointListRequest request = EntryPointListRequest.from(types);
        return entryPointResponseMapper.toResponse(
                entryPointDiscoveryApplicationService.list(repositoryId, revision, request.types()));
    }
}
