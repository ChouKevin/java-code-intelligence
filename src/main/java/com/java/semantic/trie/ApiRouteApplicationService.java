package com.java.semantic.trie;

import com.java.semantic.repository.domain.RepositoryId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public final class ApiRouteApplicationService {

    private final ApiTrieService apiTrieService;

    public ApiRouteApplicationService(ApiTrieService apiTrieService) {
        this.apiTrieService = Objects.requireNonNull(apiTrieService, "apiTrieService is required");
    }

    public List<ApiRouteCandidate> lookup(
            String apiPath,
            Optional<String> httpMethod,
            Optional<RepositoryId> repoScope) {
        Objects.requireNonNull(httpMethod, "httpMethod is required");
        Objects.requireNonNull(repoScope, "repoScope is required");
        return apiTrieService.lookupCandidates(
                        apiPath,
                        httpMethod.orElse(""),
                        repoScope.map(RepositoryId::value).orElse(""))
                .stream()
                .map(ApiRouteCandidate::from)
                .toList();
    }

    public List<ApiRouteCandidate> suggest(
            String apiPath,
            Optional<String> httpMethod,
            Optional<RepositoryId> repoScope,
            int limit) {
        Objects.requireNonNull(httpMethod, "httpMethod is required");
        Objects.requireNonNull(repoScope, "repoScope is required");
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        return apiTrieService.suggestCandidates(
                        apiPath,
                        httpMethod.orElse(""),
                        repoScope.map(RepositoryId::value).orElse(""),
                        limit)
                .stream()
                .map(ApiRouteCandidate::from)
                .toList();
    }
}
