package com.java.semantic.indexer.mcp;

import com.java.semantic.indexer.application.IndexerPreparationContract;
import com.java.semantic.indexer.application.IndexerPreparationErrorMapper;
import com.java.semantic.indexer.application.IndexerPreparationFacade;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ImportAutoConfiguration(exclude = McpServerStatelessAutoConfiguration.class)
@EnableConfigurationProperties(McpServerProperties.class)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IndexerMcpToolCatalogConfiguration {
    private static final IndexerPreparationErrorMapper ERRORS = new IndexerPreparationErrorMapper();

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer indexerMcpServer(McpStatelessServerTransport transport, McpServerProperties properties,
            @Qualifier("indexerPreparationTools") List<McpStatelessServerFeatures.SyncToolSpecification> tools) {
        // The shared contract rejects invalid inputs and supplies the same structured error as HTTP.
        // SDK pre-validation otherwise bypasses that contract and emits an unstructured text error.
        return McpServer.sync(transport).serverInfo(properties.getName(), properties.getVersion())
                .instructions(properties.getInstructions()).requestTimeout(properties.getRequestTimeout())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .immediateExecution(true).validateToolInputs(false).tools(tools).build();
    }

    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> indexerPreparationTools(
            IndexerPreparationFacade facade, ObjectMapper mapper) {
        return IndexerPreparationContract.OPERATIONS.stream().sorted().map(name -> {
            boolean lookup = name.equals("get_job");
            McpSchema.Tool tool = McpSchema.Tool.builder(name, IndexerPreparationSchemas.input(name))
                    .description(description(name)).outputSchema(IndexerPreparationSchemas.output())
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(lookup).destructiveHint(false)
                            .idempotentHint(lookup).build()).build();
            return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool)
                    .callHandler((context, request) -> invoke(name, request.arguments(), facade, mapper)).build();
        }).toList();
    }
    private static McpSchema.CallToolResult invoke(String name, Map<String, Object> fields,
            IndexerPreparationFacade facade, ObjectMapper mapper) {
        Map<String, Object> result;
        boolean error = false;
        try {
            result = switch (name) {
                case "refresh_repository_metadata" -> facade.refreshRepositoryMetadata(fields);
                case "prepare_codebase" -> facade.prepareCodebase(fields);
                case "prepare_review" -> facade.prepareReview(fields);
                case "get_job" -> facade.getJob(fields);
                default -> throw new IllegalArgumentException("unknown preparation operation");
            };
        } catch (RuntimeException exception) {
            result = ERRORS.map(exception).body();
            error = true;
        }
        return McpSchema.CallToolResult.builder().structuredContent(result)
                .addTextContent(mapper.writeValueAsString(result)).isError(error).build();
    }
    private static String description(String name) {
        return switch (name) {
            case "get_job" -> "Look up the exact repository-scoped job by jobId XOR requestId, including terminal jobs. REQUEST_NOT_FOUND means acceptance remains unknown; retry lookup only, never resubmit.";
            case "prepare_codebase" -> "Prepare the configured fixed branch for ordinary codebase reading. Save a new canonical requestId before submission. Accepted revision is pinned; poll get_job. No arbitrary branch, tag or revision.";
            case "prepare_review" -> "Prepare an explicitly selected COMMIT or direct before-to-after RANGE (full lowercase SHAs). Save a new requestId before submission. No implicit commit choice or retry; poll get_job. Lost response: look up original requestId.";
            default -> "Refresh branch catalog and history for one branch in one metadata-only job. Omitted branch uses configured default. Save a new requestId before submission; poll get_job. Lost response: look up original requestId, do not resubmit.";
        };
    }
}
