package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceReadContract;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Counts the duplicated MCP payload, with space for the outer transport metadata. */
final class SourceResponseBudget {
    // Covers the Query HTTP boundary's <=512-byte serialized request ID
    // and fixed SDK wrapper fields beyond the envelope measured below.
    static final int ENVELOPE_RESERVE_BYTES = 1024;

    private SourceResponseBudget() { }

    static int encodedBytes(Object value, ObjectMapper mapper) {
        String text = mapper.writeValueAsString(value);
        return text.getBytes(StandardCharsets.UTF_8).length + mapper.writeValueAsBytes(text).length;
    }

    static boolean fits(Object value, ObjectMapper mapper) {
        String text = mapper.writeValueAsString(value);
        Map<String, Object> result = Map.of("content", List.of(Map.of("type", "text", "text", text)),
                "structuredContent", value, "isError", false);
        int bytes = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", Long.MIN_VALUE, "result", result)).length;
        return bytes <= SourceReadContract.MAX_READ_RESPONSE_BYTES - ENVELOPE_RESERVE_BYTES;
    }
}
