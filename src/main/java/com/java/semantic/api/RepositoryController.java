package com.java.semantic.api;

import com.java.semantic.api.dto.CheckoutRepositoryRequest;
import com.java.semantic.api.dto.RepositoryStatusResponse;
import com.java.semantic.api.dto.SyncRepositoryRequest;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** 儲存庫生命週期的 authenticated HTTP API */
@RestController
@RequestMapping("/v1/repositories")
public class RepositoryController {

    private final RepositoryApplicationService repositoryApplicationService;
    private final RepositoryStatusMapper mapper;

    public RepositoryController(
            RepositoryApplicationService repositoryApplicationService,
            RepositoryStatusMapper mapper) {
        this.repositoryApplicationService = repositoryApplicationService;
        this.mapper = mapper;
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

    @PostMapping("/{repoId}/ensure")
    public RepositoryStatusResponse ensure(@PathVariable String repoId) {
        return mapper.toResponse(repositoryApplicationService.ensure(RepositoryId.of(repoId)));
    }

    @PostMapping("/{repoId}/sync")
    public RepositoryStatusResponse sync(
            @PathVariable String repoId,
            @RequestBody SyncRepositoryRequest request) {
        Optional<String> branch = Optional.ofNullable(request.branch())
                .filter(StringUtils::hasText);
        return mapper.toResponse(repositoryApplicationService.sync(RepositoryId.of(repoId), branch));
    }

    @PostMapping("/{repoId}/checkout")
    public RepositoryStatusResponse checkout(
            @PathVariable String repoId,
            @Valid @RequestBody CheckoutRepositoryRequest request) {
        return mapper.toResponse(repositoryApplicationService.checkout(
                RepositoryId.of(repoId), request.revision()));
    }
}
