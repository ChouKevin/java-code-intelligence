package com.java.semantic.document.mcp;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentFailure;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.ObjectMapper;

/** Admission and execution use exactly the same text + structured MCP envelope. */
public final class DocumentResultWriter {
    private final ObjectMapper mapper;
    public DocumentResultWriter(ObjectMapper mapper) { this.mapper = mapper; }
    public McpSchema.CallToolResult success(Object result) { return result(result, false); }
    public McpSchema.CallToolResult failure(DocumentFailure failure) {
        return result(new ErrorResult(failure.code(), failure.getMessage()), true);
    }
    public record ErrorResult(DocumentFailure.Code code, String message) { }
    private McpSchema.CallToolResult result(Object result, boolean error) {
        McpSchema.CallToolResult response = McpSchema.CallToolResult.builder().structuredContent(result)
                .addTextContent(mapper.writeValueAsString(result)).isError(error).build();
        if (mapper.writeValueAsBytes(response).length > DocumentContract.MAX_RESULT_BYTES) {
            throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE);
        }
        return response;
    }
    public int sectionWireBytes(DocumentContract.Section section) {
        // Same double encoding as structuredContent plus its JSON string in text content.
        byte[] direct = mapper.writeValueAsBytes(section);
        byte[] text = mapper.writeValueAsBytes(mapper.writeValueAsString(section));
        return direct.length + text.length;
    }
}
