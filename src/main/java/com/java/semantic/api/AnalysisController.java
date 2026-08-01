package com.java.semantic.api;

import com.java.semantic.api.dto.AnalyzeIncomingCallGraphRequest;
import com.java.semantic.api.dto.AnalyzeOutgoingCallGraphRequest;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
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
        return mapper.toResponse(service.analyzeOutgoing(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()),
                request.depth()));
    }

    @PostMapping("/call-graphs/incoming")
    public IncomingCallGraphResponse analyzeIncoming(
            @Valid @RequestBody AnalyzeIncomingCallGraphRequest request) {
        return mapper.toResponse(service.analyzeIncoming(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()),
                request.depth()));
    }
}
