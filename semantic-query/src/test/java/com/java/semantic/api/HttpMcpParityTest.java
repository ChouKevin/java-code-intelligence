package com.java.semantic.api;

import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.ContextDiscoveryService;
import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.application.GitEvidenceReadService;
import com.java.semantic.query.application.ReadContextSelector;
import com.java.semantic.query.application.ReviewManifestReadService;
import com.java.semantic.query.application.SelectedGenerationGuard;
import com.java.semantic.query.application.SelectedSemanticQueryService;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.GitEvidenceProperties;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.MongoException;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real facade/binder, with only the unavailable external storage boundary substituted. */
public class HttpMcpParityTest {
    @Test
    void valid_transport_invalid_unions_and_storage_outages_have_identical_safe_json_errors() throws Exception {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.getCollection(anyString())).thenThrow(new MongoException("mongodb://secret:password@internal-host"));
        SemanticQueryFacade facade = facade(mongo);
        JsonMapper mapper = JsonMapper.builder().build();
        QuerySecurityProperties security = new QuerySecurityProperties(); security.setApiToken("query-token");
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).addFilters(new QueryTokenFilter(security)).build();
        List<McpStatelessServerFeatures.SyncToolSpecification> tools = new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper);
        Map<String, Object> current = Map.of("kind", "CURRENT", "repositoryId", "orders", "revision", "a".repeat(40));
        assertParity(http, tools, mapper, "read_source", "/api/v1/source", Map.of("context", current, "target",
                Map.of("kind", "FACT", "factId", "b".repeat(64), "path", "src/Order.java")), 400, "INVALID_ARGUMENT");
        assertParity(http, tools, mapper, "search_code", "/api/v1/search-code", Map.of("context", current, "query", "Order", "limit", "20"), 400, "INVALID_ARGUMENT");
        assertParity(http, tools, mapper, "list_git_branches", "/api/v1/git/branches", Map.of("repositoryId", "orders"), 503, "INDEX_UNAVAILABLE");
    }

    private static void assertParity(MockMvc http, List<McpStatelessServerFeatures.SyncToolSpecification> tools, JsonMapper mapper,
            String operation, String path, Map<String, Object> input, int statusCode, String code) throws Exception {
        String payload = http.perform(post(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        McpStatelessServerFeatures.SyncToolSpecification tool = tools.stream().filter(value -> value.tool().name().equals(operation)).findFirst().orElseThrow();
        McpSchema.CallToolResult result = tool.callHandler().apply(null, McpSchema.CallToolRequest.builder(operation).arguments(input).build());
        assertThat(result.isError()).isTrue();
        assertThat(mapper.readTree(payload)).isEqualTo(mapper.valueToTree(result.structuredContent()));
        assertThat(mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text())).isEqualTo(mapper.readTree(payload));
        assertThat(mapper.readTree(payload).get("code").asText()).isEqualTo(code);
        assertThat(payload).doesNotContain("secret", "password", "internal-host", "mongodb://");
    }

    public static SemanticQueryFacade facade(MongoTemplate mongo) {
        Duration timeout = Duration.ofSeconds(2);
        ConfiguredReadPolicy policy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()),
                new GitEvidenceProperties(List.of("orders")));
        CurrentGenerationSelector current = new CurrentGenerationSelector(mongo, policy, timeout);
        SelectedGenerationGuard guard = new SelectedGenerationGuard(mongo, policy, timeout);
        ReviewManifestReadService reviews = new ReviewManifestReadService(mongo, policy, timeout);
        ReadContextSelector contexts = new ReadContextSelector(current, reviews, guard, policy);
        CodeFactReadService facts = new CodeFactReadService(mongo, guard, timeout);
        return new SemanticQueryFacade(new ContextDiscoveryService(mongo, policy, timeout, current, contexts, reviews), contexts,
                new SelectedSemanticQueryService(mongo, guard, timeout, facts), new GitEvidenceReadService(mongo, policy, timeout, facts));
    }
}
