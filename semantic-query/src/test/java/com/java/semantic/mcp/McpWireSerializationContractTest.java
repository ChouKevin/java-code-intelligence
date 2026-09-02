package com.java.semantic.mcp;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
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

class McpWireSerializationContractTest {

    private static final String REPOSITORY_ID = "orders";
    private static final String REVISION = "a".repeat(40);

    @Test
    void mcp_wire_omits_current_revision_except_for_revision_outdated_errors() throws Exception {
        JsonMapper mapper = applicationMcpMapper();
        SemanticQueryFacade invalidArgumentFacade = mock(SemanticQueryFacade.class);
        SemanticQueryFacade revisionOutdatedFacade = mock(SemanticQueryFacade.class);
        when(revisionOutdatedFacade.searchCode(any())).thenThrow(new RevisionOutdatedException(RepositoryId.of(REPOSITORY_ID),
                new RepositoryRevision(REVISION), new RepositoryRevision("b".repeat(40))));

        String invalidArgument = invoke(mapper, invalidArgumentFacade, Map.of(
                "repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "payment", "packagePrefix", " "));
        String revisionOutdated = invoke(mapper, revisionOutdatedFacade, Map.of(
                "repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "payment"));

        assertThat(invalidArgument).contains("\"code\":\"INVALID_ARGUMENT\"").doesNotContain("currentRevision");
        assertThat(revisionOutdated).contains("\"code\":\"REVISION_OUTDATED\"")
                .contains("\"currentRevision\":\"" + "b".repeat(40) + "\"");
    }

    private static String invoke(JsonMapper mapper, SemanticQueryFacade facade, Map<String, Object> arguments) throws Exception {
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
        McpServer.sync(transport).tools(new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper)).build();
        String requestBody = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "search_code", "arguments", arguments)));
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
}
