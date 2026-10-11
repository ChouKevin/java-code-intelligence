package com.java.semantic.document.api;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.config.DocumentQueryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates before reading a body, then buffers at most the fixed byte boundary. */
public final class DocumentTokenFilter extends OncePerRequestFilter {
    private final byte[] token;
    public DocumentTokenFilter(DocumentQueryProperties properties) {
        token = properties.apiToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/actuator/health".equals(path) && "GET".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (!"/mcp".equals(path)) { reject(response, 404, "NOT_FOUND", "Route is not available."); return; }
        List<String> supplied = Collections.list(request.getHeaders("X-Api-Token"));
        if (supplied.size() != 1 || !MessageDigest.isEqual(token, supplied.getFirst().getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "UNAUTHORIZED", "Document API token is required."); return;
        }
        if (!"POST".equals(request.getMethod())) {
            response.setHeader("Allow", "POST");
            reject(response, 405, "METHOD_NOT_ALLOWED", "Only stateless MCP POST is supported."); return;
        }
        try {
            String type = request.getContentType();
            if (Objects.isNull(type)) {
                reject(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use an unencoded UTF-8 JSON request."); return;
            }
            MediaType mediaType = MediaType.parseMediaType(type);
            if (!"application".equals(mediaType.getType()) || !"json".equals(mediaType.getSubtype())
                    || (Objects.nonNull(mediaType.getCharset()) && !StandardCharsets.UTF_8.equals(mediaType.getCharset()))
                    || Objects.nonNull(request.getHeader("Content-Encoding"))) {
                reject(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use an unencoded UTF-8 JSON request."); return;
            }
        } catch (IllegalArgumentException failure) {
            reject(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use an unencoded JSON request."); return;
        }
        if (request.getContentLengthLong() > DocumentContract.MAX_REQUEST_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", "Document request exceeds the body bound."); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(DocumentContract.MAX_REQUEST_BYTES + 1);
        if (bytes.length > DocumentContract.MAX_REQUEST_BYTES) {
            reject(response, 413, "REQUEST_TOO_LARGE", "Document request exceeds the body bound."); return;
        }
        chain.doFilter(new BufferedRequest(request, bytes), response);
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        private BufferedRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        @Override public int getContentLength() { return bytes.length; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Synchronous stateless request body only");
                }
            };
        }
    }
}
