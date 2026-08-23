package com.java.semantic.trie;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** 在儲存庫快照閘門後查詢指定版本的 API 路由 */
@Service
public final class ApiRouteApplicationService {

    private final ApiTrieService apiTrieService;
    private final RepositoryApplicationService repositoryApplicationService;

    public ApiRouteApplicationService(
            ApiTrieService apiTrieService,
            RepositoryApplicationService repositoryApplicationService) {
        this.apiTrieService = Objects.requireNonNull(apiTrieService, "apiTrieService is required");
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
    }

    public ApiRouteMatchBatch lookupMatches(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            String apiPath,
            Optional<String> httpMethod) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Objects.requireNonNull(httpMethod, "httpMethod is required");
        return repositoryApplicationService.withSnapshot(
                repositoryId,
                Optional.of(expectedRevision),
                ignored -> apiTrieService.lookupMatches(
                        repositoryId, expectedRevision, apiPath, httpMethod.orElse("")));
    }

    public ApiRouteMatchBatch suggestMatches(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision,
            String apiPath,
            Optional<String> httpMethod,
            int limit) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        Objects.requireNonNull(httpMethod, "httpMethod is required");
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        return repositoryApplicationService.withSnapshot(
                repositoryId,
                Optional.of(expectedRevision),
                ignored -> apiTrieService.suggestMatches(
                        repositoryId, expectedRevision, apiPath, httpMethod.orElse(""), limit));
    }
}
