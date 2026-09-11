package com.java.semantic.mcp;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.QuerySecurityProperties;
import com.java.semantic.api.QueryTokenFilter;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class McpWireSerializationContractTest {

    private static final String REPOSITORY_ID = "orders";
    private static final String REVISION = "a".repeat(40);

    @Test
    void authenticated_http_and_raw_mcp_wire_omit_absent_external_callee_fields() throws Exception {
        JsonMapper mcpMapper = applicationMcpMapper();
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        SemanticQueryContract.ProgramElement externalCallee = new SemanticQueryContract.ProgramElement(null, null,
                "client.charge(request)|client|charge|1", null);
        SemanticQueryContract.ProgramElement internalCallee = new SemanticQueryContract.ProgramElement("c".repeat(64), CodeFactKind.METHOD,
                "Orders.complete()", new SemanticQueryContract.SourceSnippet("src/Orders.java", 7, 7, "complete();"));
        SemanticQueryContract.ProgramElement namedExternalCallee = new SemanticQueryContract.ProgramElement(null, null,
                "POST https://payments.example/charge", null);
        SemanticQueryContract.CollectionResult result = new SemanticQueryContract.CollectionResult(REPOSITORY_ID, REVISION,
                List.of(new SemanticQueryContract.CalleeItem(externalCallee,
                        new SemanticQueryContract.RelationSite("b".repeat(64),
                                new SemanticQueryContract.SourceSnippet("src/Orders.java", 3, 3, "client.charge(request)")),
                        SemanticQueryContract.CalleeResolutionStatus.UNRESOLVED),
                        new SemanticQueryContract.CalleeItem(internalCallee,
                                new SemanticQueryContract.RelationSite("d".repeat(64),
                                        new SemanticQueryContract.SourceSnippet("src/Orders.java", 7, 7, "complete();")),
                                SemanticQueryContract.CalleeResolutionStatus.INDEXED),
                        new SemanticQueryContract.CalleeItem(namedExternalCallee,
                                new SemanticQueryContract.RelationSite("e".repeat(64),
                                        new SemanticQueryContract.SourceSnippet("src/Orders.java", 11, 11, "payments.charge();")),
                                SemanticQueryContract.CalleeResolutionStatus.UNINDEXED_TARGET)),
                new SemanticQueryContract.Page(0, 20, 3, 3, false));
        when(facade.findCallees(any())).thenReturn(result);

        String httpPayload = authenticatedHttp(facade).perform(post("/api/v1/callees")
                        .header(QueryTokenFilter.TOKEN_HEADER, "query-token").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","methodFactId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String mcpPayload = invoke(mcpMapper, facade, "find_callees", Map.of(
                "repositoryId", REPOSITORY_ID, "revision", REVISION, "methodFactId", "b".repeat(64)));

        assertThat(httpPayload).contains("\"callee\":{\"displayName\":\"client.charge(request)|client|charge|1\"}")
                .contains("\"callee\":{\"factId\":\"" + "c".repeat(64) + "\",\"kind\":\"METHOD\",\"displayName\":\"Orders.complete()\",\"source\"")
                .contains("\"resolutionStatus\":\"UNRESOLVED\"")
                .contains("\"resolutionStatus\":\"INDEXED\"")
                .contains("\"resolutionStatus\":\"UNINDEXED_TARGET\"")
                .doesNotContain("\"factId\":null", "\"kind\":null", "\"source\":null");
        assertThat(mcpPayload).contains("\"callee\":{\"displayName\":\"client.charge(request)|client|charge|1\"}")
                .contains("\"callee\":{\"factId\":\"" + "c".repeat(64) + "\",\"kind\":\"METHOD\",\"displayName\":\"Orders.complete()\",\"source\"")
                .contains("\"resolutionStatus\":\"UNRESOLVED\"")
                .contains("\"resolutionStatus\":\"INDEXED\"")
                .contains("\"resolutionStatus\":\"UNINDEXED_TARGET\"")
                .doesNotContain("\"factId\":null", "\"kind\":null", "\"source\":null");
        assertThat(mcpMapper.readTree(httpPayload))
                .isEqualTo(mcpMapper.readTree(mcpPayload).get("result").get("structuredContent"));
    }

    @Test
    void tools_list_serializes_the_concrete_search_success_schema() throws Exception {
        JsonMapper mapper = applicationMcpMapper();

        String payload = listTools(mapper, mock(SemanticQueryFacade.class));
        int searchToolStart = payload.indexOf("\"name\":\"search_code\"");
        int nextToolStart = payload.indexOf("\"name\":\"get_fact_source\"", searchToolStart);
        assertThat(searchToolStart).isGreaterThanOrEqualTo(0);
        assertThat(nextToolStart).isGreaterThan(searchToolStart);
        String searchTool = payload.substring(searchToolStart, nextToolStart);

        assertThat(searchTool).contains("\"outputSchema\":{")
                .contains("\"sourceCoverage\":{", "\"indexedSourceCount\"", "\"issueCount\"", "\"issueCodes\"")
                .doesNotContain("\"outputSchema\":{}");
    }

    private static String invoke(JsonMapper mapper, SemanticQueryFacade facade, Map<String, Object> arguments) throws Exception {
        return invoke(mapper, facade, "search_code", arguments);
    }

    private static String invoke(JsonMapper mapper, SemanticQueryFacade facade, String toolName, Map<String, Object> arguments) throws Exception {
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
        McpServer.sync(transport).tools(new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper)).build();
        String requestBody = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
        servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servletRequest.addHeader("Accept", "application/json, text/event-stream");
        servletRequest.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
        List<HttpMessageConverter<?>> converters = List.of(new StringHttpMessageConverter());
        ServerRequest request = ServerRequest.create(servletRequest, converters);
        HandlerFunction<ServerResponse> handler = transport.getRouterFunction().route(request).orElseThrow();
        ServerResponse response = handler.handle(request);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(servletRequest, servletResponse, () -> converters);
        return servletResponse.getContentAsString();
    }

    private static String listTools(JsonMapper mapper, SemanticQueryFacade facade) throws Exception {
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
        McpServer.sync(transport).tools(new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper)).build();
        String requestBody = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
        servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servletRequest.addHeader("Accept", "application/json, text/event-stream");
        servletRequest.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
        List<HttpMessageConverter<?>> converters = List.of(new StringHttpMessageConverter());
        ServerRequest request = ServerRequest.create(servletRequest, converters);
        HandlerFunction<ServerResponse> handler = transport.getRouterFunction().route(request).orElseThrow();
        ServerResponse response = handler.handle(request);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(servletRequest, servletResponse, () -> converters);
        return servletResponse.getContentAsString();
    }

    private static JsonMapper applicationMcpMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(McpServerJsonMapperAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean("mcpServerJsonMapper", JsonMapper.class)));
        return Objects.requireNonNull(mapper.get(), "MCP server JSON mapper is required");
    }

    private static org.springframework.test.web.servlet.MockMvc authenticatedHttp(SemanticQueryFacade facade) {
        QuerySecurityProperties securityProperties = new QuerySecurityProperties();
        securityProperties.setApiToken("query-token");
        return standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(applicationJsonMapper()))
                .addFilters(new QueryTokenFilter(securityProperties)).build();
    }

    private static JsonMapper applicationJsonMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return Objects.requireNonNull(mapper.get(), "application JSON mapper is required");
    }
}
