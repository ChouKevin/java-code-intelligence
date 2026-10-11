package com.java.semantic.document.mcp;

import com.java.semantic.document.application.DocumentCursorCodec;
import com.java.semantic.document.application.DocumentOperationGate;
import com.java.semantic.document.application.DocumentQueryService;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.catalog.GitDocumentLoader;
import com.java.semantic.document.config.DocumentQueryProperties;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/** Explicit SDK registration: neither annotation scanning nor AI tool conversion. */
@Configuration(proxyBeanMethods = false)
public class DocumentMcpConfiguration {
    @Bean @Primary
    JsonMapper documentJsonMapper() { return JsonMapper.builder().build(); }
    @Bean
    DocumentResultWriter documentResultWriter(JsonMapper mapper) { return new DocumentResultWriter(mapper); }
    @Bean(destroyMethod = "close")
    GitDocumentLoader documentLoader(DocumentQueryProperties properties, DocumentResultWriter writer) {
        return new GitDocumentLoader(properties.storage(), writer);
    }
    @Bean
    DocumentCatalog documentCatalog(DocumentQueryProperties properties, GitDocumentLoader loader) {
        return new DocumentCatalog(properties.storage(), loader);
    }
    @Bean
    DocumentCursorCodec documentCursors(JsonMapper mapper) { return new DocumentCursorCodec(mapper); }
    @Bean
    DocumentOperationGate documentOperations() { return new DocumentOperationGate(); }
    @Bean
    DocumentQueryService documentQueryService(DocumentCatalog catalog, DocumentCursorCodec cursors, DocumentOperationGate operations) {
        return new DocumentQueryService(catalog, cursors, operations);
    }
    @Bean
    DocumentQueryFacade documentQueryFacade(DocumentQueryService service, DocumentResultWriter writer) {
        return new DocumentQueryFacade(service, writer);
    }
    @Bean(destroyMethod = "close")
    DocumentToolBoundary documentToolBoundary(DocumentQueryFacade facade, DocumentResultWriter writer,
            GitDocumentLoader loader) {
        // Explicit loader dependency keeps Git open until every actual worker has unwound at shutdown.
        return new DocumentToolBoundary(facade, writer);
    }
    @Bean
    WebMvcStatelessServerTransport documentTransport(JsonMapper mapper) {
        return WebMvcStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
    }
    @Bean(destroyMethod = "close")
    McpStatelessAsyncServer documentMcpServer(WebMvcStatelessServerTransport transport, JsonMapper mapper,
            DocumentToolBoundary boundary) throws IOException {
        McpServer.StatelessAsyncSpecification specification = McpServer.async(transport)
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).serverInfo("document-query", "0.0.1")
                // SDK 2.0.0 ignores stateless requestTimeout; the owned tool boundary enforces ten seconds.
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // Facade validation provides the fixed document error envelope, including explicit null/unknown fields.
                .validateToolInputs(false);
        try (InputStream input = new ClassPathResource("document-tools.json").getInputStream()) {
            for (McpSchema.Tool tool : mapper.readValue(input, McpSchema.Tool[].class)) {
                specification.toolCall(tool, (context, request) -> boundary.execute(request.name(), request.arguments()));
            }
        }
        return specification.build();
    }
    @Bean
    RouterFunction<ServerResponse> documentMcpRoutes(WebMvcStatelessServerTransport transport, McpStatelessAsyncServer server) {
        // Server dependency installs the handler before this router is published.
        return transport.getRouterFunction();
    }
}
