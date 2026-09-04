package com.java.semantic.mcp;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticQueryFacade;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpProductionContextContractTest {

    private static final String REPOSITORY_ID = "orders";
    private static final String REVISION = "a".repeat(40);

    @Test
    void production_context_keeps_http_and_mcp_mappers_separate_and_preserves_error_wire_shape() {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        when(facade.searchCode(any())).thenThrow(new RevisionOutdatedException(RepositoryId.of(REPOSITORY_ID),
                new RepositoryRevision(REVISION), new RepositoryRevision("b".repeat(40))));

        enabledContext(facade).run(context -> {
            JsonMapper applicationMapper = context.getBean(JsonMapper.class);
            JsonMapper mcpMapper = context.getBean("mcpServerJsonMapper", JsonMapper.class);
            assertThat(applicationMapper).isNotSameAs(mcpMapper);
            RouterFunction<ServerResponse> router = router(context);

            String invalidArgument = invoke(router, mcpMapper, Map.of(
                    "repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "payment", "packagePrefix", " "));
            String revisionOutdated = invoke(router, mcpMapper, Map.of(
                    "repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "payment"));

            assertThat(invalidArgument).contains("\"code\":\"INVALID_ARGUMENT\"").doesNotContain("currentRevision");
            assertThat(revisionOutdated).contains("\"code\":\"REVISION_OUTDATED\"")
                    .contains("\"currentRevision\":\"" + "b".repeat(40) + "\"");
        });
    }

    @Test
    void disabling_mcp_leaves_only_the_boot_application_mapper() {
        disabledContext(mock(SemanticQueryFacade.class)).run(context -> {
            assertThat(context).doesNotHaveBean("mcpServerJsonMapper");
            assertThat(context).hasSingleBean(JsonMapper.class);
        });
    }

    private static WebApplicationContextRunner enabledContext(SemanticQueryFacade facade) {
        return context(facade).withPropertyValues("spring.ai.mcp.server.enabled=true", "spring.ai.mcp.server.protocol=STATELESS",
                "spring.ai.mcp.server.type=SYNC", "spring.ai.mcp.server.stdio=false");
    }

    private static WebApplicationContextRunner disabledContext(SemanticQueryFacade facade) {
        return context(facade).withPropertyValues("spring.ai.mcp.server.enabled=false");
    }

    private static WebApplicationContextRunner context(SemanticQueryFacade facade) {
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SemanticMcpConfiguration.class)
                .withBean(SemanticQueryFacade.class, () -> facade)
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
                        McpServerStatelessWebMvcAutoConfiguration.class, McpServerStatelessAutoConfiguration.class));
    }

    @SuppressWarnings("unchecked")
    private static RouterFunction<ServerResponse> router(org.springframework.boot.test.context.assertj.AssertableWebApplicationContext context) {
        return (RouterFunction<ServerResponse>) context.getBean("webMvcStatelessServerRouterFunction");
    }

    private static String invoke(RouterFunction<ServerResponse> router, JsonMapper mapper, Map<String, Object> arguments) {
        try {
            String requestBody = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "search_code", "arguments", arguments)));
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
            servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletRequest.addHeader("Accept", "application/json, text/event-stream");
            servletRequest.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
            List<HttpMessageConverter<?>> converters = List.of(new StringHttpMessageConverter());
            ServerRequest request = ServerRequest.create(servletRequest, converters);
            HandlerFunction<ServerResponse> handler = router.route(request).orElseThrow();
            ServerResponse response = handler.handle(request);
            MockHttpServletResponse servletResponse = new MockHttpServletResponse();
            response.writeTo(servletRequest, servletResponse, () -> converters);
            return servletResponse.getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException("MCP transport invocation failed", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = QueryMcpToolCatalogConfiguration.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.java\\.semantic\\.mcp\\.(QueryMcpToolCatalogConfiguration|McpServerJsonMapperConfiguration)"))
    static class SemanticMcpConfiguration {
    }
}
