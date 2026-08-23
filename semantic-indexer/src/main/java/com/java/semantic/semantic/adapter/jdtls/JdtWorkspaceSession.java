package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.eclipse.lsp4j.services.LanguageServer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 綁定啟動當下版本的單一 JDT LS 工作區
 *
 * 版本一旦不同就必須重啟:JDT LS 的索引對應的是啟動時的工作樹
 */
@Slf4j
public final class JdtWorkspaceSession {
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(5);
    private static final String VM_HWM_PREFIX = "VmHWM:";
    private static final String NON_DIGITS = "\\D";

    private final RepositoryId repositoryId;
    private final RepositoryRevision revision;
    private final JdtLsProcessFactory.LaunchHandle handle;
    private final Duration requestTimeout;
    private final JdtMonotonicTicker ticker;
    private final EnumMap<WorkspaceActivityKind, Integer> activities =
            new EnumMap<>(WorkspaceActivityKind.class);
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicLong lastUsedNanos;
    private final Object evictionLock = new Object();
    private final ReentrantLock terminationLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> documentLocks = new ConcurrentHashMap<>();
    private volatile Consumer<String> documentUriLockContentionObserver = uri -> { };

    private boolean closing;
    private volatile SemanticEngineStatus status = SemanticEngineStatus.IMPORTING;

    JdtWorkspaceSession(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            JdtLsProcessFactory.LaunchHandle handle,
            Duration requestTimeout) {
        this(repositoryId, revision, handle, requestTimeout, System::nanoTime);
    }

    JdtWorkspaceSession(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            JdtLsProcessFactory.LaunchHandle handle,
            Duration requestTimeout,
            JdtMonotonicTicker ticker) {
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        this.revision = Objects.requireNonNull(revision, "revision is required");
        this.handle = Objects.requireNonNull(handle, "handle is required");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
        this.ticker = Objects.requireNonNull(ticker, "ticker is required");
        this.lastUsedNanos = new AtomicLong(ticker.readNanos());
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }

    /** 回傳 session 啟動時綁定的版本 */
    public RepositoryRevision revision() {
        return revision;
    }

    long processId() {
        return handle.process().pid();
    }

    Process process() {
        return handle.process();
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
        synchronized (evictionLock) {
            return activityCountLocked(WorkspaceActivityKind.REQUEST);
        }
    }

    Map<WorkspaceActivityKind, Integer> activitySnapshot() {
        synchronized (evictionLock) {
            return Map.copyOf(activities);
        }
    }

    /**
     * 對同一份文件持有 session 存活期的鎖,涵蓋 didOpen、查詢與 didClose 的完整生命週期。
     *
     * 鎖不移除以避免等待中的執行緒與移除動作競爭而取得不同鎖；session 停止後整張表會一併釋放。
     */
    <T> T withDocumentUri(String uri, Supplier<T> operation) {
        Assert.hasText(uri, "uri is required");
        Assert.notNull(operation, "operation is required");
        try (WorkspaceActivityLease ignored = acquireActivity(WorkspaceActivityKind.DOCUMENT,
                "document lifecycle")) {
            ReentrantLock lock = documentLocks.computeIfAbsent(uri, key -> new ReentrantLock());
            if (!lock.tryLock()) {
                documentUriLockContentionObserver.accept(uri);
                lock.lock();
            }
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        }
    }

    void setDocumentUriLockContentionObserver(Consumer<String> observer) {
        documentUriLockContentionObserver = Objects.requireNonNull(observer, "observer is required");
    }

    /**
     * 淘汰前的原子交握:僅在沒有進行中請求時把 session 轉為 CLOSING
     *
     * 成功後 call 會拒絕新請求,呼叫端在「拿到 session」與「送出請求」之間的空窗不會被淘汰偷襲
     */
    boolean tryBeginEviction() {
        synchronized (evictionLock) {
            if (closing || totalActivityCountLocked() > 0) {
                return false;
            }
            closing = true;
            return true;
        }
    }

    /**
     * 拒絕新的工作 lease，但不影響已取得的 lease 完成。
     *
     * 管理器在發布 terminal 狀態前呼叫此 transition，讓已由呼叫端持有的 session
     * 也無法在 shutdown 與實際停止之間再送出 request 或 document lifecycle。
     */
    void rejectNewWork() {
        synchronized (evictionLock) {
            closing = true;
        }
    }

    /**
     * 登記一次 session 活動,已進入 CLOSING 就拒絕
     *
     * 與淘汰交握互斥:兩者對 closing 與活動數的判斷都在同一把鎖內完成
     */
    WorkspaceActivityLease acquireActivity(WorkspaceActivityKind kind, String operation) {
        Assert.notNull(kind, "activity kind is required");
        Assert.hasText(operation, "operation is required");
        synchronized (evictionLock) {
            if (!acceptingNewWork()) {
                throw new JdtWorkspaceClosingException(operation);
            }
            activities.merge(kind, 1, Integer::sum);
            touchLocked();
            return new WorkspaceActivityLease(() -> releaseActivity(kind));
        }
    }

    private void releaseActivity(WorkspaceActivityKind kind) {
        synchronized (evictionLock) {
            Integer activityCount = activities.get(kind);
            Assert.state(Objects.nonNull(activityCount) && activityCount > 0,
                    "activity lease is not active: " + kind);
            if (activityCount == 1) {
                activities.remove(kind);
            } else {
                activities.put(kind, activityCount - 1);
            }
            touchLocked();
        }
    }

    private int activityCountLocked(WorkspaceActivityKind kind) {
        return activities.getOrDefault(kind, 0);
    }

    private int totalActivityCountLocked() {
        int total = 0;
        for (Integer activityCount : activities.values()) {
            total += activityCount;
        }
        return total;
    }

    private boolean acceptingNewWork() {
        return !closing && !stopped.get() && !invalidated.get();
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
        try (WorkspaceActivityLease ignored = acquireActivity(WorkspaceActivityKind.REQUEST, operation)) {
            CompletableFuture<T> pending = null;
            try {
                pending = request.apply(handle.languageServer());
                return pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                cancel(pending);
                log.warn("phase=jdtls-request outcome=timeout repoId={} operation={} exceptionType={}",
                        repositoryId.value(), operation, exception.getClass().getSimpleName());
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
            }
        }
    }

    boolean markReady() {
        synchronized (evictionLock) {
            if (closing || stopped.get() || invalidated.get()) {
                return false;
            }
            status = SemanticEngineStatus.READY;
            touchLocked();
            return true;
        }
    }

    void invalidate() {
        invalidated.set(true);
    }

    void touch() {
        updateLastUsed();
    }

    private void touchLocked() {
        updateLastUsed();
    }

    private void updateLastUsed() {
        long sampledNanos = ticker.readNanos();
        lastUsedNanos.accumulateAndGet(sampledNanos, Math::max);
    }

    long lastUsedNanos() {
        return lastUsedNanos.get();
    }

    boolean isProcessAlive() {
        return handle.process().isAlive();
    }

    boolean tryBeginIdleEviction(long nowNanos, Duration idleTimeout) {
        Assert.notNull(idleTimeout, "idle timeout is required");
        Assert.isTrue(!idleTimeout.isNegative(), "idle timeout must not be negative");
        synchronized (evictionLock) {
            if (SemanticEngineStatus.READY != status
                    || !acceptingNewWork()
                    || !isProcessAlive()
                    || totalActivityCountLocked() > 0) {
                return false;
            }
            long elapsedNanos = nowNanos - lastUsedNanos.get();
            if (elapsedNanos < 0 || elapsedNanos < idleTimeout.toNanos()) {
                return false;
            }
            closing = true;
            return true;
        }
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
            log.debug("phase=jdtls-workspace outcome=peak-rss-unavailable repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    /**
     * 依 shutdown → exit → 有限等待 → 強制終結的順序停止
     *
     * shutdown 或 exit 失敗只記錄並繼續;若強制終止後仍無法確認退出,則標記 FAILED 並拋出明確錯誤
     */
    JdtProcessTerminator.TerminationResult stop() {
        terminationLock.lock();
        try {
            return stopLocked();
        } finally {
            terminationLock.unlock();
        }
    }

    Optional<JdtProcessTerminator.TerminationResult> tryStop() {
        if (!terminationLock.tryLock()) {
            return Optional.empty();
        }
        try {
            return Optional.of(stopLocked());
        } finally {
            terminationLock.unlock();
        }
    }

    private JdtProcessTerminator.TerminationResult stopLocked() {
        synchronized (evictionLock) {
            closing = true;
        }
        boolean firstStop = stopped.compareAndSet(false, true);
        if (firstStop) {
            status = SemanticEngineStatus.STOPPED;
            requestShutdown();
            requestExit();
            handle.listener().cancel(true);
        }
        if (firstStop || handle.process().isAlive()) {
            return awaitProcessExit();
        }
        return new JdtProcessTerminator.TerminationResult(true, false, false, "NONE");
    }

    private Path procStatusPath() {
        return Path.of("/proc", Long.toString(handle.process().pid()), "status");
    }

    private void requestShutdown() {
        try {
            handle.languageServer().shutdown().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("phase=jdtls-process outcome=shutdown-interrupted repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
            JdtFatalErrorPolicy.rethrowIfFatal(exception.getCause());
            log.warn("phase=jdtls-process outcome=shutdown-failed repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private void requestExit() {
        try {
            handle.languageServer().exit();
        } catch (RuntimeException exception) {
            log.warn("phase=jdtls-process outcome=exit-failed repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private JdtProcessTerminator.TerminationResult awaitProcessExit() {
        JdtProcessTerminator.TerminationResult result = JdtProcessTerminator.awaitThenForce(
                handle.process(), PROCESS_EXIT_TIMEOUT);
        if (result.terminated()) {
            String outcome = result.forced() ? "confirmed-forced-stop" : "confirmed-stop";
            log.info("phase=jdtls-process outcome={} repoId={}", outcome, repositoryId.value());
            return result;
        }
        status = SemanticEngineStatus.FAILED;
        log.error("phase=jdtls-process outcome=force-stop-unconfirmed repoId={} failureType={}",
                repositoryId.value(), result.failureType());
        throw new JdtProcessTerminationException(repositoryId, result.failureType());
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

    /** 子程序在 graceful 與 forcible 終止後仍無法確認退出。 */
    static final class JdtProcessTerminationException extends RuntimeException {

        private final String failureType;

        private JdtProcessTerminationException(RepositoryId repositoryId, String failureType) {
            super("JDT LS process termination could not be confirmed for repository "
                    + repositoryId.value() + " (failureType=" + failureType + ")", null, false, true);
            this.failureType = failureType;
        }

        String failureType() {
            return failureType;
        }
    }

    /** session 已被淘汰選中並正在關閉,不再受理新請求 */
    public static final class JdtWorkspaceClosingException extends JdtRequestFailedException {

        JdtWorkspaceClosingException(String operation) {
            super("JDT LS workspace is shutting down: " + operation, null);
        }
    }
}
