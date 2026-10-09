package com.java.semantic.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/** Rejects oversized IDs before the SDK creates a session or an SSE response. */
final class QueryMcpRequestIdBoundary implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    private static final int MAX_SERIALIZED_ID_BYTES = 512;
    private final JsonMapper mapper;

    QueryMcpRequestIdBoundary(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!HttpMethod.POST.equals(request.method())) return next.handle(request);
        // One replay buffer is necessary because the SDK owns deserialization. Scan
        // only top-level IDs, not a second object tree or nested cancellation IDs.
        byte[] body = request.servletRequest().getInputStream().readAllBytes();
        if (hasOversizedId(body)) {
            return ServerResponse.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("Request ID exceeds 512 serialized JSON bytes");
        }
        return next.handle(ServerRequest.from(request).body(body).build());
    }

    private boolean hasOversizedId(byte[] body) {
        try (JsonParser parser = mapper.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return false;
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                JsonToken token = parser.nextToken();
                if ("id".equals(name) && Objects.nonNull(token)
                        && (token == JsonToken.VALUE_STRING || token.isNumeric())) {
                    Object id = token == JsonToken.VALUE_STRING ? parser.getString() : parser.getNumberValue();
                    try {
                        mapper.writeValue(new IdSizeLimit(), id);
                    } catch (JacksonException exception) {
                        return true;
                    }
                }
                parser.skipChildren();
            }
        } catch (JacksonException exception) {
            // Malformed JSON remains the SDK's responsibility and error contract.
            return false;
        }
        return false;
    }

    private static final class IdSizeLimit extends OutputStream {
        private int bytes;

        @Override
        public void write(int value) throws IOException {
            addBytes(1);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            addBytes(length);
        }

        private void addBytes(int length) throws IOException {
            if (length > MAX_SERIALIZED_ID_BYTES - bytes) {
                throw new IOException("Request ID exceeds serialized byte limit");
            }
            bytes += length;
        }
    }
}
