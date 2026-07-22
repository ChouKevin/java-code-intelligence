package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.port.RepositoryMutationListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 每個活躍儲存庫一個 JDT LS 程序
 *
 * 每個工作區約 1 GB RSS,因此啟動彼此互斥,且淘汰只挑沒有進行中請求的 session
 */
@Service
@Slf4j
public class DefaultJdtWorkspaceManager implements JdtWorkspaceManager, RepositoryMutationListener {

    private static final String PEAK_RSS_METRIC = "jdtls.workspace.peak.rss.kilobytes";
    private static final String REPOSITORY_TAG = "repository";
    private static final Duration DEFAULT_SHUTDOWN_LOCK_WAIT = Duration.ofSeconds(5);

    private final JdtLsProcessFactory processFactory;
    private final JdtLsReadinessProbe readinessProbe;
    private final JdtLsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<RepositoryId, JdtWorkspaceSession> sessions = new ConcurrentHashMap<>();
    private final Map<RepositoryId, LaunchTracker> launchingProcesses = new ConcurrentHashMap<>();
    private final Map<RepositoryId, SemanticEngineStatus> transientStatuses = new ConcurrentHashMap<>();
    private final Map<RepositoryId, Meter.Id> gauges = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Object processRegistryLock = new Object();
    private final Duration shutdownLockWait;
    private volatile boolean terminated;

    @Autowired
    public DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry) {
        this(processFactory, readinessProbe, properties, meterRegistry, DEFAULT_SHUTDOWN_LOCK_WAIT);
    }

    DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry,
            Duration shutdownLockWait) {
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory is required");
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
        this.shutdownLockWait = Objects.requireNonNull(shutdownLockWait, "shutdownLockWait is required");
    }

    @Override
    public JdtWorkspaceSession getOrStart(RepositorySnapshot snapshot) {
        Assert.notNull(snapshot, "snapshot is required");
        ensureActive(snapshot.repositoryId());
        Optional<JdtWorkspaceSession> running = runningSession(snapshot);
        if (running.isPresent()) {
            return running.get();
        }
        lifecycleLock.lock();
        try {
            ensureActive(snapshot.repositoryId());
            return runningSession(snapshot).orElseGet(() -> startLocked(snapshot));
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** shutdownAll(@PreDestroy)之後不得再啟動工作區,否則會漏出一個沒有東西會去停止的 ~1 GB 程序 */
    private void ensureActive(RepositoryId repositoryId) {
        if (terminated) {
            throw new JdtWorkspaceManagerStoppedException(repositoryId);
        }
    }

    @Override
    public SemanticEngineStatus status(RepositoryId repositoryId) {
        Assert.notNull(repositoryId, "repositoryId is required");
        JdtWorkspaceSession session = sessions.get(repositoryId);
        if (Objects.nonNull(session)) {
            return session.status();
        }
        return transientStatuses.getOrDefault(repositoryId, SemanticEngineStatus.STOPPED);
    }

    /**
     * 停止工作區,讓下一次查詢重新啟動
     *
     * 失敗一律包成 RepositoryMutationException:listener 例外會沿著 notifyBeforeMutation 逸出,
     * 未被 ApiExceptionHandler 對應到的型別會變成沒有 errorCode 的 500,破壞 API 合約
     *
     * 這裡刻意不取 lifecycleLock:呼叫端持有 B1 的寫鎖,若還要等一個進行中的匯入(上限 900 秒)
     * 才能失效,整個儲存庫都會被鎖住;改由 session 的 invalidated 旗標讓啟動中的 probe 自行中止
     */
    @Override
    public void invalidate(RepositoryId repositoryId) {
        Assert.notNull(repositoryId, "repositoryId is required");
        try {
            JdtWorkspaceSession session = sessions.get(repositoryId);
            if (Objects.isNull(session)) {
                return;
            }
            session.invalidate();
            session.stop();
            removeStoppedSession(repositoryId, session);
            log.info("phase=jdtls-workspace outcome=invalidated repoId={}", repositoryId.value());
        } catch (RuntimeException exception) {
            String failureType = exception instanceof JdtWorkspaceSession.JdtProcessTerminationException termination
                    ? termination.failureType()
                    : exception.getClass().getSimpleName();
            log.warn("phase=jdtls-workspace outcome=failed repoId={} exceptionType={}",
                    repositoryId.value(), failureType);
            throw new RepositoryMutationException(
                    "semantic workspace invalidation failed (failureType=" + failureType + ")");
        }
    }

    /** Git 變更前先失效工作區,此時仍在 B1 的寫鎖內 */
    @Override
    public void beforeMutation(RepositoryId repositoryId) {
        invalidate(repositoryId);
    }

    /**
     * 關閉所有工作區,先設終結旗標擋掉新啟動,再有界等待生命週期鎖
     *
     * 啟動路徑會握著鎖跨越 900 秒的匯入上限;容器在冷啟動途中關閉時 @PreDestroy 不能一直等,
     * 否則會被 SIGKILL 而讓 JDT LS 子程序變成孤兒,因此等不到鎖就照樣停掉當下 map 裡看得到的 session
     */
    @Override
    @PreDestroy
    public void shutdownAll() {
        synchronized (processRegistryLock) {
            terminated = true;
        }
        boolean acquired = acquireLifecycleLock();
        try {
            for (RepositoryId repositoryId : Set.copyOf(sessions.keySet())) {
                stopQuietly(repositoryId);
            }
            for (RepositoryId repositoryId : Set.copyOf(launchingProcesses.keySet())) {
                stopLaunchingProcess(repositoryId);
            }
            transientStatuses.keySet().removeIf(repositoryId ->
                    !sessions.containsKey(repositoryId) && !launchingProcesses.containsKey(repositoryId));
        } finally {
            if (acquired) {
                lifecycleLock.unlock();
            }
        }
    }

    Set<Long> activeProcessIds() {
        return Stream.concat(
                        sessions.values().stream().map(JdtWorkspaceSession::processId),
                        launchingProcesses.values().stream()
                                .map(LaunchTracker::process)
                                .flatMap(Optional::stream)
                                .filter(Process::isAlive)
                                .map(Process::pid))
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean acquireLifecycleLock() {
        try {
            return lifecycleLock.tryLock(shutdownLockWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("phase=jdtls-workspace outcome=shutdown-lock-interrupted exceptionType={}",
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private Optional<JdtWorkspaceSession> runningSession(RepositorySnapshot snapshot) {
        JdtWorkspaceSession session = sessions.get(snapshot.repositoryId());
        if (Objects.isNull(session)
                || SemanticEngineStatus.READY != session.status()
                || !session.isUsable()
                || session.isInvalidated()
                || !session.revision().equals(snapshot.revision())) {
            return Optional.empty();
        }
        session.touch();
        return Optional.of(session);
    }

    private JdtWorkspaceSession startLocked(RepositorySnapshot snapshot) {
        RepositoryId repositoryId = snapshot.repositoryId();
        reconcileRetainedLaunch(repositoryId);
        discardUnusable(repositoryId);
        makeRoomFor(repositoryId);
        transientStatuses.put(repositoryId, SemanticEngineStatus.STARTING);
        JdtLsReadinessProbe.ImportProgressClient client = readinessProbe.newClient();
        JdtLsProcessFactory.LaunchHandle launchHandle = launch(snapshot, client);
        JdtWorkspaceSession session = new JdtWorkspaceSession(
                repositoryId,
                snapshot.revision(),
                launchHandle,
                properties.getRequestTimeout());
        try {
            publishSession(repositoryId, session, launchHandle.process());
            readinessProbe.awaitReady(session, client, snapshot.root());
            ensureStartupStillOwned(repositoryId, session);
        } catch (Throwable failure) {
            cleanupStartup(repositoryId, session, startupFailureStatus(repositoryId, session));
            JdtFatalErrorPolicy.rethrowIfFatal(failure);
            if (failure instanceof Error error) {
                throw launchFailure(repositoryId, "JDT LS startup failed", error);
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw new IllegalStateException("unexpected JDT LS startup failure", failure);
        }
        log.info("phase=jdtls-workspace outcome=ready repoId={} revision={}",
                repositoryId.value(), snapshot.revision().value());
        return session;
    }

    @SuppressWarnings("removal")
    private JdtLsProcessFactory.LaunchHandle launch(
            RepositorySnapshot snapshot, JdtLsReadinessProbe.ImportProgressClient client) {
        RepositoryId repositoryId = snapshot.repositoryId();
        Path workspaceData = properties.getWorkspaceDataRoot().resolve(repositoryId.value());
        LaunchTracker tracker = beginLaunch(repositoryId);
        try {
            return processFactory.launch(
                    snapshot.root(), workspaceData, client,
                    process -> registerLaunchingProcess(repositoryId, tracker, process));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ensureActive(repositoryId);
            throw launchFailure(repositoryId, "JDT LS launch was interrupted", exception);
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException exception) {
            ensureActive(repositoryId);
            throw launchFailure(repositoryId, "JDT LS launch failed", exception);
        } catch (Error error) {
            JdtFatalErrorPolicy.rethrowIfFatal(error);
            ensureActive(repositoryId);
            throw launchFailure(repositoryId, "JDT LS launch failed", error);
        } finally {
            tracker.complete();
            removeTerminatedLaunchingProcess(repositoryId, tracker);
        }
    }

    private LaunchTracker beginLaunch(RepositoryId repositoryId) {
        synchronized (processRegistryLock) {
            ensureActive(repositoryId);
            LaunchTracker tracker = new LaunchTracker();
            LaunchTracker existing = launchingProcesses.putIfAbsent(repositoryId, tracker);
            if (Objects.nonNull(existing)) {
                throw new JdtWorkspaceTerminationPendingException(repositoryId);
            }
            return tracker;
        }
    }

    private void reconcileRetainedLaunch(RepositoryId repositoryId) {
        LaunchTracker tracker = launchingProcesses.get(repositoryId);
        if (Objects.isNull(tracker)) {
            return;
        }
        stopLaunchingProcess(repositoryId);
        if (launchingProcesses.containsKey(repositoryId)) {
            throw new JdtWorkspaceTerminationPendingException(repositoryId);
        }
    }

    private void registerLaunchingProcess(
            RepositoryId repositoryId, LaunchTracker tracker, Process process) {
        tracker.processStarted(process);
        synchronized (processRegistryLock) {
            if (tracker.isCancelled()) {
                throw new JdtWorkspaceManagerStoppedException(repositoryId);
            }
            ensureActive(repositoryId);
        }
    }

    private void publishSession(
            RepositoryId repositoryId, JdtWorkspaceSession session, Process process) {
        synchronized (processRegistryLock) {
            ensureActive(repositoryId);
            sessions.put(repositoryId, session);
            LaunchTracker tracker = launchingProcesses.get(repositoryId);
            if (Objects.nonNull(tracker) && tracker.owns(process)) {
                launchingProcesses.remove(repositoryId, tracker);
            }
            transientStatuses.remove(repositoryId);
            registerPeakRssGauge(session);
        }
    }

    private void removeTerminatedLaunchingProcess(RepositoryId repositoryId, LaunchTracker tracker) {
        Optional<Process> process = tracker.process();
        if (tracker.isComplete() && (process.isEmpty() || !process.get().isAlive())) {
            launchingProcesses.remove(repositoryId, tracker);
            if (terminated) {
                transientStatuses.remove(repositoryId);
            }
        }
    }

    private JdtLsReadinessProbe.JdtWorkspaceStartupException launchFailure(
            RepositoryId repositoryId, String reason, Throwable cause) {
        transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
        String failureType = cause.getClass().getSimpleName();
        log.warn("phase=jdtls-workspace outcome=failed repoId={} reason={} exceptionType={}",
                repositoryId.value(), reason, failureType);
        return new JdtLsReadinessProbe.JdtWorkspaceStartupException(
                repositoryId, reason, "", failureType);
    }

    private void discardUnusable(RepositoryId repositoryId) {
        if (sessions.containsKey(repositoryId)) {
            log.info("phase=jdtls-workspace outcome=discarded repoId={}", repositoryId.value());
            stopQuietly(repositoryId);
        }
    }

    private void ensureStartupStillOwned(RepositoryId repositoryId, JdtWorkspaceSession session) {
        if (terminated) {
            throw new JdtWorkspaceManagerStoppedException(repositoryId);
        }
        if (!Objects.equals(sessions.get(repositoryId), session)) {
            throw new JdtLsReadinessProbe.JdtWorkspaceStartupException(
                    repositoryId, "JDT LS startup session was removed", "", "NONE");
        }
        if (SemanticEngineStatus.READY != session.status() || !session.isUsable()) {
            throw new JdtLsReadinessProbe.JdtWorkspaceStartupException(
                    repositoryId, "JDT LS session became unavailable while importing", "", "NONE");
        }
    }

    private SemanticEngineStatus startupFailureStatus(
            RepositoryId repositoryId, JdtWorkspaceSession session) {
        return terminated || !Objects.equals(sessions.get(repositoryId), session)
                ? SemanticEngineStatus.STOPPED
                : SemanticEngineStatus.FAILED;
    }

    private void cleanupStartup(
            RepositoryId repositoryId, JdtWorkspaceSession session, SemanticEngineStatus status) {
        stopAfterFailure(session);
        synchronized (processRegistryLock) {
            if (session.isProcessAlive()) {
                sessions.putIfAbsent(repositoryId, session);
                removeLaunchTracker(repositoryId, session.process());
                if (!gauges.containsKey(repositoryId)) {
                    registerPeakRssGauge(session);
                }
                transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
                return;
            }
            sessions.remove(repositoryId, session);
            removeLaunchTracker(repositoryId, session.process());
            removeGauge(repositoryId);
            transientStatuses.put(repositoryId, status);
        }
    }

    /**
     * 容量已滿時淘汰最久未用且沒有進行中請求的 session
     *
     * 用 tryBeginEviction 原子交握取代「先讀 activeRequests 再停止」的檢查後動作:
     * 兩步之間若有請求落地,舊寫法會把有進行中請求的 session 拆掉,讓呼叫端等到逾時
     */
    private void makeRoomFor(RepositoryId repositoryId) {
        removeCompletedTerminatedLaunches();
        while (trackedWorkspaceCount() >= properties.getMaxActiveWorkspaces()) {
            JdtWorkspaceSession evictable = sessions.values().stream()
                    .sorted(Comparator.comparingLong(JdtWorkspaceSession::lastUsedNanos))
                    .filter(JdtWorkspaceSession::tryBeginEviction)
                    .findFirst()
                    .orElseThrow(() -> new JdtWorkspaceCapacityException(repositoryId));
            log.info("phase=jdtls-workspace outcome=evicted repoId={} requestedBy={}",
                    evictable.repositoryId().value(), repositoryId.value());
            stopQuietly(evictable.repositoryId());
        }
    }

    private void removeCompletedTerminatedLaunches() {
        launchingProcesses.forEach(this::removeTerminatedLaunchingProcess);
    }

    private int trackedWorkspaceCount() {
        return sessions.size() + launchingProcesses.size();
    }

    /** 清理失敗的啟動,但不讓停止失敗覆蓋原始失敗 */
    private void stopAfterFailure(JdtWorkspaceSession session) {
        try {
            session.stop();
        } catch (RuntimeException stopFailure) {
            log.warn(
                    "phase=jdtls-workspace outcome=failed-stop repoId={} exceptionType={}",
                    session.repositoryId().value(), stopFailure.getClass().getSimpleName());
        }
    }

    private void stopQuietly(RepositoryId repositoryId) {
        JdtWorkspaceSession session = sessions.get(repositoryId);
        if (Objects.isNull(session)) {
            return;
        }
        try {
            session.stop();
            removeStoppedSession(repositoryId, session);
        } catch (RuntimeException exception) {
            log.warn("phase=jdtls-workspace outcome=stop-failed repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private void removeStoppedSession(RepositoryId repositoryId, JdtWorkspaceSession session) {
        synchronized (processRegistryLock) {
            if (session.isProcessAlive()) {
                return;
            }
            sessions.remove(repositoryId, session);
            transientStatuses.remove(repositoryId);
            removeGauge(repositoryId);
        }
    }

    private void stopLaunchingProcess(RepositoryId repositoryId) {
        LaunchTracker tracker = launchingProcesses.get(repositoryId);
        if (Objects.isNull(tracker)) {
            return;
        }
        tracker.cancel();
        Optional<Process> process = tracker.process();
        if (process.isPresent()) {
            JdtProcessTerminator.TerminationResult result = JdtProcessTerminator.destroyThenAwait(
                    process.get(), DEFAULT_SHUTDOWN_LOCK_WAIT);
            if (!result.terminated()) {
                transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
                log.error("phase=jdtls-process outcome=launch-stop-unconfirmed repoId={} failureType={}",
                        repositoryId.value(), result.failureType());
                return;
            }
        }
        boolean launchCompleted = tracker.awaitCompletion(shutdownLockWait);
        if (launchCompleted) {
            launchingProcesses.remove(repositoryId, tracker);
            transientStatuses.remove(repositoryId);
            log.info("phase=jdtls-process outcome=confirmed-launch-stop repoId={}", repositoryId.value());
            return;
        }
        transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
        log.warn("phase=jdtls-process outcome=launch-cancel-pending repoId={}", repositoryId.value());
    }

    private void removeLaunchTracker(RepositoryId repositoryId, Process process) {
        LaunchTracker tracker = launchingProcesses.get(repositoryId);
        if (Objects.nonNull(tracker) && tracker.owns(process)) {
            launchingProcesses.remove(repositoryId, tracker);
        }
    }

    private void registerPeakRssGauge(JdtWorkspaceSession session) {
        Gauge gauge = Gauge.builder(PEAK_RSS_METRIC, session,
                        tracked -> tracked.peakResidentKilobytes().orElse(0L))
                .tag(REPOSITORY_TAG, session.repositoryId().value())
                .description("JDT LS 子程序的尖峰常駐記憶體")
                .baseUnit("kilobytes")
                .register(meterRegistry);
        gauges.put(session.repositoryId(), gauge.getId());
    }

    private void removeGauge(RepositoryId repositoryId) {
        Meter.Id gaugeId = gauges.remove(repositoryId);
        if (Objects.nonNull(gaugeId)) {
            meterRegistry.remove(gaugeId);
        }
    }

    /** 容量已滿且每個工作區都有進行中請求 */
    public static final class JdtWorkspaceCapacityException extends RuntimeException {

        JdtWorkspaceCapacityException(RepositoryId repositoryId) {
            super("no idle JDT LS workspace can be evicted for repository " + repositoryId.value());
        }
    }

    /** 管理器已關閉,不再啟動新的工作區 */
    public static final class JdtWorkspaceManagerStoppedException extends RuntimeException {

        JdtWorkspaceManagerStoppedException(RepositoryId repositoryId) {
            super("JDT LS workspace manager has shut down; repository "
                    + repositoryId.value() + " cannot be started");
        }
    }

    /** 前一次啟動留下的子程序尚未確認停止,不得以新 tracker 覆蓋。 */
    public static final class JdtWorkspaceTerminationPendingException extends RuntimeException {

        JdtWorkspaceTerminationPendingException(RepositoryId repositoryId) {
            super("JDT LS process termination is still pending for repository " + repositoryId.value());
        }
    }

    private static final class LaunchTracker {

        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CountDownLatch completed = new CountDownLatch(1);

        private void processStarted(Process startedProcess) {
            process.set(Objects.requireNonNull(startedProcess, "startedProcess is required"));
        }

        private Optional<Process> process() {
            return Optional.ofNullable(process.get());
        }

        private boolean owns(Process candidate) {
            return process.get() == candidate;
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean isCancelled() {
            return cancelled.get();
        }

        private void complete() {
            completed.countDown();
        }

        private boolean isComplete() {
            return completed.getCount() == 0;
        }

        private boolean awaitCompletion(Duration timeout) {
            try {
                return completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
