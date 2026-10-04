package com.java.semantic.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/** Bridges MCP notifications/cancelled to the SDK tools/call execution thread.
 * The process runner owns termination/reaping when interrupted; this bridge owns only
 * request correlation. Concurrent duplicate JSON-RPC IDs cannot steal a live slot.
 */
public final class CancellableMcpTransport implements McpStatelessServerTransport {
    private final McpStatelessServerTransport delegate;
    private final Map<Object, ActiveCall> active = new ConcurrentHashMap<>();

    public CancellableMcpTransport(McpStatelessServerTransport delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                    McpSchema.JSONRPCRequest request) {
                if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) return handler.handleRequest(context, request);
                return Mono.defer(() -> {
                    ActiveCall call = new ActiveCall(Thread.currentThread());
                    if (Objects.nonNull(active.putIfAbsent(request.id(), call))) {
                        return Mono.just(McpSchema.JSONRPCResponse.error(request.id(),
                                new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST,
                                        "Concurrent duplicate request ID")));
                    }
                    try {
                        return handler.handleRequest(context, request).doFinally(signal -> {
                            call.finish();
                            active.remove(request.id(), call);
                        });
                    } catch (RuntimeException exception) {
                        call.finish();
                        active.remove(request.id(), call);
                        throw exception;
                    }
                });
            }

            @Override
            public Mono<Void> handleNotification(McpTransportContext context,
                    McpSchema.JSONRPCNotification notification) {
                if (!"notifications/cancelled".equals(notification.method())) {
                    return handler.handleNotification(context, notification);
                }
                return Mono.fromRunnable(() -> {
                    if (notification.params() instanceof Map<?, ?> params) {
                        Object requestId = params.get("requestId");
                        if (!(requestId instanceof String || requestId instanceof Integer || requestId instanceof Long)) return;
                        ActiveCall call = active.get(requestId);
                        if (Objects.nonNull(call)) call.cancel();
                    }
                });
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }

    private static final class ActiveCall {
        private final Thread owner;
        private boolean open = true;
        private boolean cancelled;

        private ActiveCall(Thread owner) { this.owner = owner; }

        synchronized void cancel() {
            if (!open) return;
            cancelled = true;
            owner.interrupt();
        }

        synchronized void finish() {
            open = false;
            if (cancelled && Thread.currentThread() == owner) Thread.interrupted();
        }
    }
}
