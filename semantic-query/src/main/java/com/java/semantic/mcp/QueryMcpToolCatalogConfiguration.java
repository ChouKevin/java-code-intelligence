package com.java.semantic.mcp;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.query.source.SourceQueryException;
import com.java.semantic.query.application.SemanticQueryErrorMapper;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One application operation and JSON result for both transports. */
@Configuration
@ImportAutoConfiguration(exclude = McpServerAutoConfiguration.class)
@EnableConfigurationProperties(McpServerProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QueryMcpToolCatalogConfiguration {
    private static final SemanticQueryErrorMapper ERRORS = new SemanticQueryErrorMapper();

    @Bean("mcpServerJsonMapper")
    JsonMapper mcpServerJsonMapper(JsonMapper.Builder builder) {
        return builder.build();
    }

    @Bean
    @Primary
    McpStreamableServerTransportProvider sessionOwnedMcpTransport(
            WebMvcStreamableServerTransportProvider delegate) {
        return new SessionOwnedMcpTransport(delegate);
    }

    @Bean
    public RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(
            WebMvcStreamableServerTransportProvider delegate,
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper) {
        return delegate.getRouterFunction().filter(new QueryMcpRequestIdBoundary(mapper));
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer queryMcpServer(McpStreamableServerTransportProvider transport, McpServerProperties properties,
            @Qualifier("mcpQueryToolSpecifications") List<McpServerFeatures.SyncToolSpecification> tools) {
        // SDK schema rejection bypasses the shared structured application error contract.
        return McpServer.sync(transport).serverInfo(properties.getName(), properties.getVersion())
                .instructions(properties.getInstructions()).requestTimeout(properties.getRequestTimeout())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .immediateExecution(true).validateToolInputs(false).tools(tools).build();
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpQueryToolSpecifications(
            SemanticQueryFacade facade, ObjectMapper mapper) {
        return SemanticMcpToolCatalog.tools().stream().map(definition -> {
            McpSchema.Tool tool = McpSchema.Tool.builder(definition.name(), SemanticMcpSchemaCatalog.inputSchema(definition.name()))
                    .description(definition.description()).outputSchema(SemanticMcpSchemaCatalog.outputSchema(definition.name()))
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                            .idempotentHint(true).build()).build();
            return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                    .callHandler((context, request) -> invoke(definition.name(), request.arguments(), facade, mapper)).build();
        }).toList();
    }

    private static McpSchema.CallToolResult invoke(String operation, Map<String, Object> arguments,
            SemanticQueryFacade facade, ObjectMapper mapper) {
        Object result;
        boolean error = false;
        try {
            result = facade.execute(operation, arguments);
        } catch (RuntimeException exception) {
            result = ERRORS.map(exception);
            error = true;
        }
        McpSchema.CallToolResult response = callResult(result, error, mapper);
        if (!error && isIndexOperation(operation)
                && mapper.writeValueAsBytes(response).length > IndexContract.MAX_TRANSPORT_BYTES) {
            return callResult(ERRORS.map(new IndexFailure(IndexFailure.Code.INDEX_RESULT_TOO_LARGE)), true, mapper);
        }
        if (!error && operation.equals("list_repositories")
                && mapper.writeValueAsBytes(response).length > IndexContract.MAX_TRANSPORT_BYTES) {
            return callResult(ERRORS.map(new SourceQueryException(SourceQueryException.Code.SOURCE_RESULT_TOO_LARGE)), true, mapper);
        }
        return response;
    }

    private static McpSchema.CallToolResult callResult(Object result, boolean error, ObjectMapper mapper) {
        return McpSchema.CallToolResult.builder().structuredContent(result)
                .addTextContent(mapper.writeValueAsString(result)).isError(error).build();
    }

    private static boolean isIndexOperation(String operation) {
        return switch (operation) {
            case "search_code", "get_outline", "list_entry_points", "find_relations" -> true;
            default -> false;
        };
    }
}
