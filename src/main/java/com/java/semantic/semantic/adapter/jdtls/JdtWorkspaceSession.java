package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 綁定啟動當下版本的單一 JDT LS 工作區
 *
 * 版本一旦不同就必須重啟:JDT LS 的索引對應的是啟動時的工作樹
 */
public final class JdtWorkspaceSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdtWorkspaceSession.class);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String VM_HWM_PREFIX = "VmHWM:";
    private static final String NON_DIGITS = "\\D";

    private final RepositoryId repositoryId;
    private final RepositoryRevision revision;
    private final JdtLsProcessFactory.LaunchHandle handle;
    private final Duration requestTimeout;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong lastUsedNanos = new AtomicLong(System.nanoTime());
    private final Object evictionLock = new Object();

    private boolean closing;
    private volatile SemanticEngineStatus status = SemanticEngineStatus.IMPORTING;

    JdtWorkspaceSession(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            JdtLsProcessFactory.LaunchHandle handle,
            Duration requestTimeout) {
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        this.revision = Objects.requireNonNull(revision, "revision is required");
        this.handle = Objects.requireNonNull(handle, "handle is required");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }

    /** 回傳 session 啟動時綁定的版本 */
    public RepositoryRevision revision() {
        return revision;
    }

    public SemanticEngineStatus status() {
        if (SemanticEngineStatus.READY == status && !isUsable()) {
            return SemanticEngineStatus.FAILED;
        }
        return status;
    }

    /** 工作樹即將變更或已變更,session 不可再使用 */
    public boolean isInvalidated() {
        return invalidated.get();
    }

    /** 進行中的請求數,大於零的 session 不可被淘汰 */
    public int activeRequests() {
        return activeRequests.get();
    }

    /**
     * 淘汰前的原子交握:僅在沒有進行中請求時把 session 轉為 CLOSING
     *
     * 成功後 call 會拒絕新請求,呼叫端在「拿到 session」與「送出請求」之間的空窗不會被淘汰偷襲
     */
    boolean tryBeginEviction() {
        synchronized (evictionLock) {
            if (closing || activeRequests.get() != 0) {
                return false;
            }
            closing = true;
            return true;
        }
    }

    /**
     * 登記一次進行中的請求,已進入 CLOSING 就拒絕
     *
     * 與 tryBeginEviction 互斥:兩者對 closing 與 activeRequests 的判斷都在同一把鎖內完成
     */
    private boolean tryBeginRequest() {
        synchronized (evictionLock) {
            if (closing) {
                return false;
            }
            activeRequests.incrementAndGet();
            return true;
        }
    }

    /** 失敗診斷用的 stderr 最後數行 */
    public StderrRingBuffer stderrBuffer() {
        return handle.stderrBuffer();
    }

    /**
     * 以逾時上限送出一次 LSP 請求,並計入進行中請求數
     *
     * JDT LS 無回應時 future 會永遠不完成,沒有逾時包裝就會拖住呼叫端執行緒
     * 已被淘汰選中(CLOSING)的 session 直接拒絕新請求,避免對即將終結的程序送出會逾時掛住的呼叫
     */
    public <T> T call(String operation, Function<LanguageServer, CompletableFuture<T>> request) {
        Assert.hasText(operation, "operation is required");
        Assert.notNull(request, "request is required");
        if (!tryBeginRequest()) {
            throw new JdtWorkspaceClosingException(operation);
        }
        CompletableFuture<T> pending = null;
        try {
            pending = request.apply(handle.languageServer());
            return pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            cancel(pending);
            throw new JdtRequestTimeoutException(
                    "JDT LS request timed out after " + requestTimeout + ": " + operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(pending);
            throw new JdtRequestFailedException(failureMessage(operation), exception);
        } catch (ExecutionException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception.getCause());
            throw new JdtRequestFailedException(failureMessage(operation), exception.getCause());
        } catch (RuntimeException exception) {
            throw new JdtRequestFailedException(failureMessage(operation), exception);
        } finally {
            activeRequests.decrementAndGet();
            touch();
        }
    }

    boolean markReady() {
        synchronized (evictionLock) {
            if (closing || stopped.get() || invalidated.get()) {
                return false;
            }
            status = SemanticEngineStatus.READY;
            return true;
        }
    }

    void invalidate() {
        invalidated.set(true);
    }

    void touch() {
        lastUsedNanos.set(System.nanoTime());
    }

    long lastUsedNanos() {
        return lastUsedNanos.get();
    }

    boolean isProcessAlive() {
        return handle.process().isAlive();
    }

    boolean isUsable() {
        synchronized (evictionLock) {
            return !closing
                    && !stopped.get()
                    && !invalidated.get()
                    && isProcessAlive()
                    && !handle.stderrDrain().isCompletedExceptionally();
        }
    }

    /**
     * 讀取子程序的尖峰常駐記憶體
     *
     * spike 實測每個工作區約 1 GB RSS,且幾乎不受 -Xmx 影響;容器大小要看這個值而不是 heap
     */
    OptionalLong peakResidentKilobytes() {
        try (Stream<String> lines = Files.lines(procStatusPath())) {
            return lines.filter(line -> line.startsWith(VM_HWM_PREFIX))
                    .map(line -> line.replaceAll(NON_DIGITS, ""))
                    .filter(StringUtils::hasText)
                    .mapToLong(Long::parseLong)
                    .findFirst();
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("JDT LS peak RSS unavailable: repositoryId={} failureType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    /**
     * 依 shutdown → exit → 有限等待 → 強制終結的順序停止
     *
     * 停止是盡力而為:shutdown 或 exit 失敗只記錄並繼續,程序仍必須被終結
     */
    void stop() {
        synchronized (evictionLock) {
            closing = true;
        }
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        status = SemanticEngineStatus.STOPPED;
        requestShutdown();
        requestExit();
        handle.listener().cancel(true);
        awaitProcessExit();
    }

    private Path procStatusPath() {
        return Path.of("/proc", Long.toString(handle.process().pid()), "status");
    }

    private void requestShutdown() {
        try {
            handle.languageServer().shutdown().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("JDT LS shutdown interrupted: repositoryId={} failureType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
            JdtFatalErrorPolicy.rethrowIfFatal(exception.getCause());
            LOGGER.warn("JDT LS shutdown request failed: repositoryId={} failureType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private void requestExit() {
        try {
            handle.languageServer().exit();
        } catch (RuntimeException exception) {
            LOGGER.warn("JDT LS exit notification failed: repositoryId={} failureType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private void awaitProcessExit() {
        Process process = handle.process();
        try {
            if (process.waitFor(PROCESS_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("JDT LS exit wait interrupted: repositoryId={} failureType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
        LOGGER.warn("JDT LS did not exit gracefully, forcing termination: repositoryId={}",
                repositoryId.value());
        process.destroyForcibly();
    }

    private String failureMessage(String operation) {
        return "JDT LS request failed: " + operation;
    }

    private void cancel(CompletableFuture<?> pending) {
        if (Objects.nonNull(pending)) {
            pending.cancel(true);
        }
    }

    /** LSP 請求失敗 */
    public static class JdtRequestFailedException extends RuntimeException {

        JdtRequestFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** LSP 請求在逾時上限內沒有回應 */
    public static final class JdtRequestTimeoutException extends JdtRequestFailedException {

        JdtRequestTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** session 已被淘汰選中並正在關閉,不再受理新請求 */
    public static final class JdtWorkspaceClosingException extends JdtRequestFailedException {

        JdtWorkspaceClosingException(String operation) {
            super("JDT LS workspace is shutting down: " + operation, null);
        }
    }
}
