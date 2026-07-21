package com.java.semantic.api;

import com.java.semantic.api.dto.ApiRouteCandidatesResponse;
import com.java.semantic.api.dto.ApiRouteLookupRequest;
import com.java.semantic.api.dto.ApiRouteSuggestRequest;
import com.java.semantic.repository.domain.RepositoryId;
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
        return mapper.toResponse(service.lookup(
                request.apiPath(),
                optionalText(request.httpMethod()),
                optionalRepository(request.repoScope())));
    }

    @PostMapping("/suggest")
    public ApiRouteCandidatesResponse suggest(
            @Valid @RequestBody ApiRouteSuggestRequest request) {
        return mapper.toResponse(service.suggest(
                request.apiPath(),
                optionalText(request.httpMethod()),
                optionalRepository(request.repoScope()),
                request.limit()));
    }

    private Optional<String> optionalText(String value) {
        return Optional.ofNullable(value).filter(StringUtils::hasText);
    }

    private Optional<RepositoryId> optionalRepository(String value) {
        return optionalText(value).map(RepositoryId::of);
    }
}
