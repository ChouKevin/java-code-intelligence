package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the registered router with a client-owned SDK Streamable HTTP session. */
final class McpWireTestClient {
    private final WebMvcStreamableServerTransportProvider transport;
    private final JsonMapper mapper;
    private final String session;

    McpWireTestClient(WebMvcStreamableServerTransportProvider transport, JsonMapper mapper) throws Exception {
        this.transport = transport;
        this.mapper = mapper;
        Response initialized = request(transport, "POST", mapper.writeValueAsString(java.util.Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", java.util.Map.of(
                        "protocolVersion", "2025-06-18", "capabilities", java.util.Map.of(),
                        "clientInfo", java.util.Map.of("name", "test-client", "version", "1")))), null);
        assertThat(initialized.status()).isEqualTo(200);
        assertThat(mapper.readTree(initialized.body()).get("result").get("protocolVersion").asString())
                .isEqualTo("2025-06-18");
        session = Objects.requireNonNull(initialized.session());
        request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    String session() { return session; }

    Response request(String body) throws Exception { return request(transport, "POST", body, session); }
    Response request(String body, Function<MockHttpServletResponse, HttpServletResponse> responseSurface)
            throws Exception {
        return request(transport, "POST", body, session, responseSurface);
    }

    JsonNode call(String body) throws Exception {
        Response response = request(body);
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(jsonBody(response.body()));
    }

    void delete() throws Exception { assertThat(request(transport, "DELETE", "", session).status()).isEqualTo(200); }

    static Response request(WebMvcStreamableServerTransportProvider transport, String method, String body,
            String session) throws Exception {
        return request(transport, method, body, session, output -> output);
    }

    private static Response request(WebMvcStreamableServerTransportProvider transport, String method, String body,
            String session, Function<MockHttpServletResponse, HttpServletResponse> responseSurface) throws Exception {
        MockHttpServletRequest servlet = new MockHttpServletRequest(method, "/mcp");
        servlet.setAsyncSupported(true);
        servlet.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servlet.addHeader("Accept", "application/json, text/event-stream");
        if (Objects.nonNull(session)) {
            servlet.addHeader("Mcp-Session-Id", session);
            servlet.addHeader("MCP-Protocol-Version", "2025-06-18");
        }
        servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
        List<HttpMessageConverter<?>> converters = List.of(new StringHttpMessageConverter(),
                new JacksonJsonHttpMessageConverter());
        ServerRequest input = ServerRequest.create(servlet, converters);
        ServerResponse response = transport.getRouterFunction().route(input).orElseThrow().handle(input);
        MockHttpServletResponse output = new MockHttpServletResponse();
        response.writeTo(servlet, responseSurface.apply(output), () -> converters);
        return new Response(output.getStatus(), output.getHeader("Mcp-Session-Id"),
                new String(output.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    static String jsonBody(String wire) {
        if (wire.startsWith("{")) return wire;
        return wire.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5).trim();
    }

    record Response(int status, String session, String body) {}
}
