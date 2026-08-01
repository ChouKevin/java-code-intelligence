package com.java.semantic.api;

import com.java.semantic.api.dto.ResolveSourceSymbolRequest;
import com.java.semantic.api.dto.SourceSymbolResolutionResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.SourceSymbolContext;
import com.java.semantic.syntax.application.SourceSymbolResolutionApplicationService;
import com.java.semantic.syntax.application.SourceSymbolResolutionQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** source-only symbol resolution 的 stateless HTTP boundary */
@RestController
@RequestMapping("/v1/discovery/source-symbols")
public final class SourceSymbolResolutionController {

    private final SourceSymbolResolutionApplicationService service;

    private final SourceSymbolResolutionResponseMapper mapper;

    public SourceSymbolResolutionController(
            SourceSymbolResolutionApplicationService service,
            SourceSymbolResolutionResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @PostMapping("/resolve")
    public SourceSymbolResolutionResponse resolve(@Valid @RequestBody ResolveSourceSymbolRequest request) {
        SourceSymbolContext context = new SourceSymbolContext(
                request.context().type(),
                request.context().sourceFile(),
                request.context().method().map(method -> new SourceSymbolContext.MethodContext(
                        method.name(), method.parameterTypes())));
        SourceSymbolResolutionQuery query = new SourceSymbolResolutionQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                context,
                request.symbol(),
                request.position().map(position -> position.toDomain()));
        return mapper.toResponse(service.resolve(query));
    }
}
