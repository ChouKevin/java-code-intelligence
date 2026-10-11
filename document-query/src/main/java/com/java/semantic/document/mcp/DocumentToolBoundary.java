package com.java.semantic.document.mcp;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentFailure;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** SDK async tool boundary only: synchronous work owns its resources independently of the response. */
public final class DocumentToolBoundary implements AutoCloseable {
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private final DocumentQueryFacade facade;
    private final McpSchema.CallToolResult busy;
    private final McpSchema.CallToolResult timeout;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(DocumentContract.MAX_ACTIVE_OPERATIONS,
            DocumentContract.MAX_ACTIVE_OPERATIONS, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            runnable -> new Thread(runnable, "document-tool-worker"), new ThreadPoolExecutor.AbortPolicy());
    private final Scheduler responseTimer = Schedulers.newSingle("document-response-timeout");

    public DocumentToolBoundary(DocumentQueryFacade facade, DocumentResultWriter writer) {
        this.facade = facade;
        this.busy = writer.failure(new DocumentFailure(DocumentFailure.Code.DOCUMENT_BUSY));
        this.timeout = writer.failure(new DocumentFailure(DocumentFailure.Code.DOCUMENT_TIMEOUT));
    }

    public Mono<McpSchema.CallToolResult> execute(String tool, Map<String, Object> arguments) {
        return Mono.<McpSchema.CallToolResult>create(response -> {
            FutureTask<Void> work = new FutureTask<>(() -> {
                try { response.success(facade.execute(tool, arguments)); }
                catch (Throwable failure) { response.error(failure); }
                return null;
            });
            // Cancellation requests cooperative interruption, never closes the service's permit or lease.
            response.onCancel(() -> work.cancel(true));
            try { workers.execute(work); }
            catch (RejectedExecutionException rejected) { response.success(busy); }
        }).timeout(RESPONSE_TIMEOUT, Mono.just(timeout), responseTimer);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        boolean interrupted = false;
        try {
            // A blocked local filesystem call cannot safely be force-killed. The owning bean depends on
            // GitDocumentLoader, so Spring must finish this drain before closing the borrowed Git resource.
            while (!workers.isTerminated()) {
                try { workers.awaitTermination(1, TimeUnit.DAYS); }
                catch (InterruptedException failure) { interrupted = true; }
            }
        } finally {
            responseTimer.dispose();
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
