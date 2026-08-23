package com.java.semantic.mcp.provider;

import com.java.semantic.mcp.McpQueryProvider;
import com.java.semantic.mcp.McpQueryRegistration;
import com.java.semantic.mcp.dto.route.ApiRouteMcpDtos;
import com.java.semantic.mcp.mapper.ApiRouteMcpMapper;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.trie.ApiRouteApplicationService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 發布以 repository revision gate 保護的 API route MCP 查詢 */
@Component
public final class ApiRouteMcpTools implements McpQueryProvider {

    private final ApiRouteApplicationService applicationService;
    private final ApiRouteMcpMapper mapper;

    public ApiRouteMcpTools(ApiRouteApplicationService applicationService, ApiRouteMcpMapper mapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public List<McpQueryRegistration<?, ?>> registrations() {
        return List.of(
                new McpQueryRegistration<>(
                        "semantic_lookup_api_routes",
                        "Look up exact API route candidates at one repository revision",
                        ApiRouteMcpDtos.LookupInput.class,
                        ApiRouteMcpDtos.Output.class,
                        this::lookup),
                new McpQueryRegistration<>(
                        "semantic_suggest_api_routes",
                        "Suggest bounded API route candidates at one repository revision",
                        ApiRouteMcpDtos.SuggestInput.class,
                        ApiRouteMcpDtos.Output.class,
                        this::suggest));
    }

    private ApiRouteMcpDtos.Output lookup(ApiRouteMcpDtos.LookupInput input) {
        return mapper.routes(applicationService.lookupMatches(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.apiPath(),
                optionalText(input.httpMethod())));
    }

    private ApiRouteMcpDtos.Output suggest(ApiRouteMcpDtos.SuggestInput input) {
        return mapper.routes(applicationService.suggestMatches(
                RepositoryId.of(input.repoId()),
                new RepositoryRevision(input.expectedRevision()),
                input.apiPath(),
                optionalText(input.httpMethod()),
                input.limit()));
    }

    private Optional<String> optionalText(String value) {
        return Optional.ofNullable(value).filter(StringUtils::hasText);
    }
}
