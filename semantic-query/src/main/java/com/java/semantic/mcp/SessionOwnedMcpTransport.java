package com.java.semantic.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps request IDs and cancellation within the SDK's initialized MCP session. */
public final class SessionOwnedMcpTransport implements McpStreamableServerTransportProvider {
    // Source searches and process cleanup share a five-second deadline; allow a second
    // for the transport to finish its terminal signal, then fail instead of returning 200.
    private static final Duration CLEANUP_WAIT = Duration.ofSeconds(6);

    private final McpStreamableServerTransportProvider delegate;
    private final Set<ActiveCall> running = ConcurrentHashMap.newKeySet();
    private boolean closing;

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
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            synchronized (this) {
                closing = true;
                running.forEach(ActiveCall::cancel);
            }
            // The SDK provider may discard sessions after invoking closeGracefully; keep
            // the live-call completion fence independently of its session map.
            return delegate.closeGracefully().then(Mono.defer(() -> awaitCleanup(running)));
        });
    }

    private static Mono<Void> awaitCleanup(Iterable<ActiveCall> calls) {
        ArrayList<CompletableFuture<Void>> completed = new ArrayList<>();
        calls.forEach(call -> completed.add(call.completed));
        return Mono.fromFuture(CompletableFuture.allOf(completed.toArray(CompletableFuture[]::new)))
                .timeout(CLEANUP_WAIT);
    }

    private final class OwnedSession extends McpStreamableServerSession {
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
                synchronized (SessionOwnedMcpTransport.this) {
                    synchronized (this) {
                        if (closing || closed) return Mono.empty();
                        if (Objects.nonNull(active.putIfAbsent(request.id(), call))) {
                            return transport.sendMessage(McpSchema.JSONRPCResponse.error(request.id(),
                                    new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST,
                                            "Concurrent duplicate request ID"))).then(transport.closeGracefully());
                        }
                        running.add(call);
                    }
                }
                try {
                    return delegate.responseStream(request, new ResponseTransport(transport, call))
                            .doFinally(signal -> finish(request.id(), call));
                } catch (RuntimeException exception) {
                    finish(request.id(), call);
                    throw exception;
                }
            });
        }

        private void finish(Object requestId, ActiveCall call) {
            call.finish();
            active.remove(requestId, call);
            running.remove(call);
            call.completed.complete(null);
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
        public Mono<Void> delete() {
            return Mono.defer(() -> awaitCleanup(cancelAll()).then(Mono.defer(delegate::delete)));
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.defer(() -> awaitCleanup(cancelAll()).then(Mono.defer(delegate::closeGracefully)));
        }

        @Override
        public void close() { cancelAll(); delegate.close(); }

        private synchronized Iterable<ActiveCall> cancelAll() {
            closed = true;
            List<ActiveCall> current = List.copyOf(active.values());
            current.forEach(ActiveCall::cancel);
            return current;
        }
    }

    /**
     * The SDK calls sendMessage after its tool handler has produced a result. Release
     * only this call's cancellation interrupt before entering servlet response I/O.
     */
    private record ResponseTransport(McpStreamableServerTransport delegate, ActiveCall call)
            implements McpStreamableServerTransport {
        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.defer(() -> {
                call.beginResponse();
                return delegate.sendMessage(message);
            });
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return delegate.sendMessage(message, messageId);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
            return delegate.unmarshalFrom(data, type);
        }

        @Override
        public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }

        @Override
        public void close() { delegate.close(); }
    }

    private static final class ActiveCall {
        private final Thread owner;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private boolean open = true;
        private boolean cancelled;

        ActiveCall(Thread owner) { this.owner = owner; }

        synchronized void cancel() {
            if (!open) return;
            cancelled = true;
            owner.interrupt();
        }

        synchronized void beginResponse() {
            open = false;
            if (cancelled && Thread.currentThread() == owner) {
                Thread.interrupted();
                cancelled = false;
            }
        }

        synchronized void finish() {
            open = false;
            if (cancelled && Thread.currentThread() == owner) Thread.interrupted();
        }
    }
}
