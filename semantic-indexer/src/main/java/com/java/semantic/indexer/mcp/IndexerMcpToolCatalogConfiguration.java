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
                    .description(description(name)).outputSchema(IndexerPreparationSchemas.output(name))
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(lookup).destructiveHint(false)
                            .idempotentHint(lookup).build()).build();
            return McpStatelessServerFeatures.SyncToolSpecification.builder().tool(tool)
                    .callHandler((context, request) -> invoke(name, request.arguments(), facade, mapper)).build();
        }).toList();
    }

    private static String description(String operation) {
        return switch (operation) {
            case "prepare_source" -> "Privately submit durable source preparation. Omit revision to fetch the configured default-branch HEAD, pin its SHA and update current only upon successful publication. Provide an exact full SHA to prepare historical membership only: current remains unchanged, even if the SHA equals current. Neither mode automatically prepares a syntax index. Persist a new canonical requestId before calling; recover uncertain acceptance with get_job and that original ID, never resubmit. Source COMPLETE means published original source, not index readiness.";
            case "prepare_semantic_index" -> "Privately build a syntax index and Java folds, plus static Relations only when the operator explicitly enabled approved frozen inputs, for the already-published exact current SHA. Persist a new canonical requestId after source COMPLETE. Non-current or absent-current fails INDEX_NOT_CURRENT; never substitute another SHA. Retained older generations remain readable but cannot be rebuilt while historical. No Git fetch, current change, repository build, target execution or dependency download occurs. Acceptance is not READY; recover uncertainty with get_job and the original ID. Source stays readable on failure. Static declaration resolution is not runtime dispatch; framework evidence is always candidate.";
            case "get_job" -> "Read one repository-scoped durable source or semantic preparation job. Supply exactly one of jobId or the original requestId; use that original requestId to recover uncertain acceptance, never resubmit as a new job. Inspect phase and failureCode: acceptance is not READY, source and index readiness are independent, and coverage may be partial. Published profile/capabilities distinguish syntax, folds and opt-in static Relations, not runtime behavior. This lookup does not prepare work or switch revision.";
            default -> throw new IllegalArgumentException("unknown preparation operation");
        };
    }

    private static McpSchema.CallToolResult invoke(String name, Map<String, Object> fields,
            IndexerPreparationFacade facade, ObjectMapper mapper) {
        Map<String, Object> result;
        boolean error = false;
        try {
            result = switch (name) {
                case "get_job" -> facade.getJob(fields);
                case "prepare_source" -> facade.prepareSource(fields);
                case "prepare_semantic_index" -> facade.prepareSemanticIndex(fields);
                default -> throw new IllegalArgumentException("unknown preparation operation");
            };
        } catch (RuntimeException exception) {
            result = ERRORS.map(exception).body();
            error = true;
        }
        return McpSchema.CallToolResult.builder().structuredContent(result)
                .addTextContent(mapper.writeValueAsString(result)).isError(error).build();
    }
}
