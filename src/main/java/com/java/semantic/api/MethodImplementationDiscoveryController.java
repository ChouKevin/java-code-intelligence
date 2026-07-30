package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoverMethodImplementationsRequest;
import com.java.semantic.api.dto.DiscoverMethodImplementationsResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryApplicationService;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 方法實作探索的無狀態 HTTP 邊界 */
@RestController
@RequestMapping("/v1/discovery")
public final class MethodImplementationDiscoveryController {

    private final MethodImplementationDiscoveryApplicationService service;
    private final MethodImplementationDiscoveryResponseMapper mapper;

    public MethodImplementationDiscoveryController(
            MethodImplementationDiscoveryApplicationService service,
            MethodImplementationDiscoveryResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @PostMapping("/method-implementations")
    public DiscoverMethodImplementationsResponse discover(
            @Valid @RequestBody DiscoverMethodImplementationsRequest request) {
        MethodImplementationDiscoveryQuery query = new MethodImplementationDiscoveryQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.declarationTarget().toDomain());
        return mapper.toResponse(service.discover(query));
    }
}
