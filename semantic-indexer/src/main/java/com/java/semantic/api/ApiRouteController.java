package com.java.semantic.api;

import com.java.semantic.api.dto.ApiRouteCandidatesResponse;
import com.java.semantic.api.dto.ApiRouteLookupRequest;
import com.java.semantic.api.dto.ApiRouteSuggestRequest;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.trie.ApiRouteApplicationService;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/v1/api-routes")
public final class ApiRouteController {

    private final ApiRouteApplicationService service;
    private final ApiRouteResponseMapper mapper;

    public ApiRouteController(
            ApiRouteApplicationService service,
            ApiRouteResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @PostMapping("/lookup")
    public ApiRouteCandidatesResponse lookup(
            @Valid @RequestBody ApiRouteLookupRequest request) {
        return mapper.toResponse(service.lookupMatches(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.apiPath(),
                optionalText(request.httpMethod())));
    }

    @PostMapping("/suggest")
    public ApiRouteCandidatesResponse suggest(
            @Valid @RequestBody ApiRouteSuggestRequest request) {
        return mapper.toResponse(service.suggestMatches(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.apiPath(),
                optionalText(request.httpMethod()),
                request.limit()));
    }

    private Optional<String> optionalText(String value) {
        return Optional.ofNullable(value).filter(StringUtils::hasText);
    }

}
