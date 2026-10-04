package com.java.semantic.mcp;

import com.java.semantic.query.application.SemanticQueryErrorMapper;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.util.List;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    McpStreamableServerTransportProvider sessionOwnedMcpTransport(WebMvcStreamableServerTransportProvider delegate) {
        return new SessionOwnedMcpTransport(delegate);
    }

    @Bean(destroyMethod = "close")
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
                    .callHandler((context, request) -> {
                        Object result;
                        boolean error = false;
                        try {
                            result = facade.execute(definition.name(), request.arguments());
                        } catch (RuntimeException exception) {
                            result = ERRORS.map(exception);
                            error = true;
                        }
                        return McpSchema.CallToolResult.builder().structuredContent(result)
                                .addTextContent(mapper.writeValueAsString(result)).isError(error).build();
                    }).build();
        }).toList();
    }
}
