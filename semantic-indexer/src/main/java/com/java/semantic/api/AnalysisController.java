package com.java.semantic.api;

import com.java.semantic.api.dto.AnalyzeIncomingCallGraphRequest;
import com.java.semantic.api.dto.AnalyzeOutgoingCallGraphRequest;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Stateless HTTP boundary for incoming and outgoing graph-fragment operations. */
@RestController
@RequestMapping("/v1/analyses")
public final class AnalysisController {

    private final SemanticAnalysisApplicationService service;
    private final AnalysisResponseMapper mapper;

    public AnalysisController(
            SemanticAnalysisApplicationService service,
            AnalysisResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @PostMapping("/call-graphs/outgoing")
    public OutgoingCallGraphResponse analyzeOutgoing(
            @Valid @RequestBody AnalyzeOutgoingCallGraphRequest request) {
        RepositoryId repositoryId = RepositoryId.of(request.repoId());
        return mapper.toResponse(repositoryId, service.analyzeOutgoing(
                repositoryId,
                new RepositoryRevision(request.expectedRevision()),
                JavaSourceIdentityHttpMapper.toDomain(request.target()),
                request.depth()));
    }

    @PostMapping("/call-graphs/incoming")
    public IncomingCallGraphResponse analyzeIncoming(
            @Valid @RequestBody AnalyzeIncomingCallGraphRequest request) {
        RepositoryId repositoryId = RepositoryId.of(request.repoId());
        return mapper.toResponse(repositoryId, service.analyzeIncoming(
                repositoryId,
                new RepositoryRevision(request.expectedRevision()),
                JavaSourceIdentityHttpMapper.toDomain(request.target()),
                request.depth()));
    }
}
