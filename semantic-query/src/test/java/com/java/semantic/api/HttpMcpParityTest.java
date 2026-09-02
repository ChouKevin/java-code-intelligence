package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HttpMcpParityTest {

    @Test
    void authenticated_http_and_mcp_return_the_same_success_and_revision_outdated_application_bodies() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        SemanticQueryContract.RepositoryCollection repositories = new SemanticQueryContract.RepositoryCollection(
                List.of(new SemanticQueryContract.RepositoryItem("orders", "a".repeat(40))),
                new SemanticQueryContract.Page(0, 20, 1, 1, false));
        when(facade.listRepositories(any())).thenReturn(repositories);
        RevisionOutdatedException outdated = new RevisionOutdatedException(RepositoryId.of("orders"),
                new RepositoryRevision("a".repeat(40)), new RepositoryRevision("b".repeat(40)));
        when(facade.searchCode(any())).thenThrow(outdated);

        QuerySecurityProperties securityProperties = new QuerySecurityProperties();
        securityProperties.setApiToken("query-token");
        Object controller = Class.forName("com.java.semantic.api.SemanticQueryController")
                .getDeclaredConstructor(SemanticQueryFacade.class).newInstance(facade);
        MockMvc http = standaloneSetup(controller).setControllerAdvice(new QueryApiExceptionHandler())
                .addFilters(new QueryTokenFilter(securityProperties)).build();
        String httpSuccess = http.perform(get("/api/v1/repositories").header(QueryTokenFilter.TOKEN_HEADER, "query-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String httpFailure = http.perform(post("/api/v1/search-code").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                .contentType("application/json").content("""
                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"payment"}
                """))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, new ObjectMapper());
        McpSchema.CallToolResult mcpSuccess = call(specifications, "list_repositories", Map.of());
        McpSchema.CallToolResult mcpFailure = call(specifications, "search_code", Map.of(
                "repositoryId", "orders", "revision", "a".repeat(40), "query", "payment"));
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.readTree(httpSuccess)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpSuccess.structuredContent())));
        assertThat(mapper.readTree(httpFailure)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpFailure.structuredContent())));
    }

    private static McpSchema.CallToolResult call(List<McpStatelessServerFeatures.SyncToolSpecification> specifications,
                                                  String toolName, Map<String, Object> arguments) {
        return specifications.stream().filter(specification -> toolName.equals(specification.tool().name())).findFirst().orElseThrow()
                .callHandler().apply(null, new McpSchema.CallToolRequest(toolName, arguments));
    }
}
