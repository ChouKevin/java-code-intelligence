package com.java.semantic.api;

import com.java.semantic.api.dto.AnalyzeCallGraphRequest;
import com.java.semantic.api.dto.AnalysisResponse;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

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

    @PostMapping("/call-graph")
    public AnalysisResponse<ExplainableCallGraph> analyze(
            @Valid @RequestBody AnalyzeCallGraphRequest request) {
        return mapper.toResponse(service.analyze(
                RepositoryId.of(request.repoId()),
                expectedRevision(request.expectedRevision()),
                request.packageName(),
                request.className(),
                request.methodSignature()));
    }

    @PostMapping("/call-graph/flatten")
    public AnalysisResponse<FlattenedCallGraph> analyzeFlattened(
            @Valid @RequestBody AnalyzeCallGraphRequest request) {
        return mapper.toResponse(service.analyzeFlattened(
                RepositoryId.of(request.repoId()),
                expectedRevision(request.expectedRevision()),
                request.packageName(),
                request.className(),
                request.methodSignature()));
    }

    private Optional<RepositoryRevision> expectedRevision(String value) {
        return Optional.ofNullable(value).map(RepositoryRevision::new);
    }
}
