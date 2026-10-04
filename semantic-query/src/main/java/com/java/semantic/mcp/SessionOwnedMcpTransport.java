package com.java.semantic.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps request IDs and cancellation within the SDK's initialized MCP session. */
public final class SessionOwnedMcpTransport implements McpStreamableServerTransportProvider {
    private final McpStreamableServerTransportProvider delegate;

    public SessionOwnedMcpTransport(McpStreamableServerTransportProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public List<String> protocolVersions() { return delegate.protocolVersions(); }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        delegate.setSessionFactory(initialize -> {
            McpStreamableServerSession.McpStreamableServerSessionInit created = factory.startSession(initialize);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(
                    new OwnedSession(created.session()), created.initResult());
        });
    }

    @Override
    public Mono<Void> notifyClients(String method, Object message) { return delegate.notifyClients(method, message); }

    @Override
    public Mono<Void> notifyClient(String session, String method, Object message) {
        return delegate.notifyClient(session, method, message);
    }

    @Override
    public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }

    private static final class OwnedSession extends McpStreamableServerSession {
        private final McpStreamableServerSession delegate;
        private final Map<Object, ActiveCall> active = new ConcurrentHashMap<>();
        private boolean closed;

        OwnedSession(McpStreamableServerSession delegate) {
            super(delegate.getId(), null, null, Duration.ZERO, Map.of(), Map.of());
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> responseStream(McpSchema.JSONRPCRequest request, McpStreamableServerTransport transport) {
            if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) return delegate.responseStream(request, transport);
            return Mono.defer(() -> {
                ActiveCall call = new ActiveCall(Thread.currentThread());
                synchronized (this) {
                    if (closed) return Mono.empty();
                    if (Objects.nonNull(active.putIfAbsent(request.id(), call))) {
                        return transport.sendMessage(McpSchema.JSONRPCResponse.error(request.id(),
                                new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST,
                                        "Concurrent duplicate request ID"))).then(transport.closeGracefully());
                    }
                }
                try {
                    return delegate.responseStream(request, transport).doFinally(signal -> {
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
        public Mono<Void> accept(McpSchema.JSONRPCNotification notification) {
            if (!"notifications/cancelled".equals(notification.method())) return delegate.accept(notification);
            return Mono.fromRunnable(() -> {
                if (notification.params() instanceof Map<?, ?> params) {
                    Object requestId = params.get("requestId");
                    if (!(requestId instanceof String || requestId instanceof Integer || requestId instanceof Long)) return;
                    ActiveCall call = active.get(requestId);
                    if (Objects.nonNull(call)) call.cancel();
                }
            });
        }

        @Override
        public Mono<Void> accept(McpSchema.JSONRPCResponse response) { return delegate.accept(response); }

        @Override
        public <T> Mono<T> sendRequest(String method, Object params, TypeRef<T> type) {
            return delegate.sendRequest(method, params, type);
        }

        @Override
        public Mono<Void> sendNotification(String method, Object params) {
            return delegate.sendNotification(method, params);
        }

        @Override
        public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) {
            return delegate.listeningStream(transport);
        }

        @Override
        public Flux<McpSchema.JSONRPCMessage> replay(Object eventId) { return delegate.replay(eventId); }

        @Override
        public Mono<Void> delete() { return Mono.defer(() -> { cancelAll(); return delegate.delete(); }); }

        @Override
        public Mono<Void> closeGracefully() { return Mono.defer(() -> { cancelAll(); return delegate.closeGracefully(); }); }

        @Override
        public void close() { cancelAll(); delegate.close(); }

        private synchronized void cancelAll() {
            closed = true;
            active.values().forEach(ActiveCall::cancel);
        }
    }

    private static final class ActiveCall {
        private final Thread owner;
        private boolean open = true;
        private boolean cancelled;

        ActiveCall(Thread owner) { this.owner = owner; }

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
