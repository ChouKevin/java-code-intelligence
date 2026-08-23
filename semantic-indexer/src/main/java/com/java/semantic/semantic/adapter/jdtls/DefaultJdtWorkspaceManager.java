package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
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
    private final JdtWorkspaceLifecycleMetrics lifecycleMetrics;
    private final Map<RepositoryId, JdtWorkspaceSession> sessions = new ConcurrentHashMap<>();
    private final Map<RepositoryId, LaunchTracker> launchingProcesses = new ConcurrentHashMap<>();
    private final Map<RepositoryId, SemanticEngineStatus> transientStatuses = new ConcurrentHashMap<>();
    private final Map<RepositoryId, Meter.Id> gauges = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Object processRegistryLock = new Object();
    private final Duration shutdownLockWait;
    private final JdtMonotonicTicker ticker;
    private final Runnable launchCompletionWaitObserver;
    private volatile boolean terminated;

    @Autowired
    public DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry,
            JdtMonotonicTicker ticker,
            JdtWorkspaceLifecycleMetrics lifecycleMetrics) {
        this(processFactory, readinessProbe, properties, meterRegistry, DEFAULT_SHUTDOWN_LOCK_WAIT, ticker,
                lifecycleMetrics);
    }

    DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry,
            JdtWorkspaceLifecycleMetrics lifecycleMetrics) {
        this(processFactory, readinessProbe, properties, meterRegistry, DEFAULT_SHUTDOWN_LOCK_WAIT, System::nanoTime,
                lifecycleMetrics);
    }

    /**
     * A registry's singleton manager binds exactly one metrics owner created from that registry.
     */
    DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry,
            Duration shutdownLockWait,
            JdtMonotonicTicker ticker,
            JdtWorkspaceLifecycleMetrics lifecycleMetrics) {
        this(processFactory, readinessProbe, properties, meterRegistry, shutdownLockWait, ticker,
                lifecycleMetrics, () -> { });
    }

    /** 僅供 package-private lifecycle race test 精確觀察取消後的 completion wait 邊界。 */
    DefaultJdtWorkspaceManager(
            JdtLsProcessFactory processFactory,
            JdtLsReadinessProbe readinessProbe,
            JdtLsProperties properties,
            MeterRegistry meterRegistry,
            Duration shutdownLockWait,
            JdtMonotonicTicker ticker,
            JdtWorkspaceLifecycleMetrics lifecycleMetrics,
            Runnable launchCompletionWaitObserver) {
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory is required");
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
        this.lifecycleMetrics = Objects.requireNonNull(lifecycleMetrics, "lifecycleMetrics is required");
        this.shutdownLockWait = Objects.requireNonNull(shutdownLockWait, "shutdownLockWait is required");
        this.ticker = Objects.requireNonNull(ticker, "ticker is required");
        this.launchCompletionWaitObserver = Objects.requireNonNull(
                launchCompletionWaitObserver, "launchCompletionWaitObserver is required");
        this.lifecycleMetrics.bind(this::lifecycleSnapshot);
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
            stopSession(session, JdtWorkspaceLifecycleMetrics.EvictionTrigger.INVALIDATION);
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
            sessions.values().forEach(JdtWorkspaceSession::rejectNewWork);
            terminated = true;
        }
        boolean acquired = acquireLifecycleLock();
        try {
            for (RepositoryId repositoryId : Set.copyOf(sessions.keySet())) {
                stopQuietly(repositoryId, JdtWorkspaceLifecycleMetrics.EvictionTrigger.SHUTDOWN, false);
            }
            for (RepositoryId repositoryId : Set.copyOf(launchingProcesses.keySet())) {
                stopLaunchingProcess(repositoryId, JdtWorkspaceLifecycleMetrics.EvictionTrigger.SHUTDOWN);
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

    WorkspaceActivityLease acquireActivity(
            RepositoryId repositoryId, RepositoryRevision revision, WorkspaceActivityKind kind) {
        Assert.notNull(repositoryId, "repositoryId is required");
        Assert.notNull(revision, "revision is required");
        Assert.notNull(kind, "activity kind is required");
        synchronized (processRegistryLock) {
            JdtWorkspaceSession session = sessions.get(repositoryId);
            if (terminated
                    || Objects.isNull(session)
                    || !revision.equals(session.revision())
                    || SemanticEngineStatus.READY != session.status()
                    || !session.isUsable()) {
                throw new JdtWorkspaceSession.JdtWorkspaceClosingException("workspace activity");
            }
            return session.acquireActivity(kind, "workspace activity");
        }
    }

    MaintenanceReport runMaintenance() {
        if (terminated) {
            return MaintenanceReport.empty();
        }
        if (!lifecycleLock.tryLock()) {
            return MaintenanceReport.busy();
        }
        List<MaintenanceCandidate> candidates;
        try {
            if (terminated) {
                return MaintenanceReport.empty();
            }
            candidates = snapshotMaintenanceCandidates();
        } finally {
            lifecycleLock.unlock();
        }
        int confirmedRetries = 0;
        int idleEvictions = 0;
        int retainedProcesses = 0;
        for (MaintenanceCandidate candidate : candidates) {
            MaintenanceCandidateResult result = terminateMaintenanceCandidate(candidate);
            confirmedRetries += result.confirmedRetries();
            idleEvictions += result.idleEvictions();
            retainedProcesses += result.retainedProcesses();
        }
        return new MaintenanceReport(false, confirmedRetries, idleEvictions, retainedProcesses);
    }

    private List<MaintenanceCandidate> snapshotMaintenanceCandidates() {
        List<MaintenanceCandidate> candidates = new ArrayList<>();
        for (Map.Entry<RepositoryId, LaunchTracker> entry : new ArrayList<>(launchingProcesses.entrySet())) {
            LaunchTracker tracker = entry.getValue();
            if (tracker.isComplete() && tracker.isProcessAlive()) {
                candidates.add(MaintenanceCandidate.retryingTracker(entry.getKey(), tracker));
            }
        }
        long nowNanos = ticker.readNanos();
        for (Map.Entry<RepositoryId, JdtWorkspaceSession> entry : new ArrayList<>(sessions.entrySet())) {
            RepositoryId repositoryId = entry.getKey();
            JdtWorkspaceSession session = entry.getValue();
            if (!session.isProcessAlive()) {
                continue;
            }
            if (isRetryEligible(session)) {
                candidates.add(MaintenanceCandidate.retryingSession(repositoryId, session));
            } else if (session.tryBeginIdleEviction(nowNanos, properties.getIdleTimeout())) {
                candidates.add(MaintenanceCandidate.idleSession(repositoryId, session));
            }
        }
        return List.copyOf(candidates);
    }

    private boolean isRetryEligible(JdtWorkspaceSession session) {
        return SemanticEngineStatus.FAILED == session.status()
                || session.isInvalidated()
                || !session.isUsable();
    }

    private MaintenanceCandidateResult terminateMaintenanceCandidate(MaintenanceCandidate candidate) {
        try {
            if (Objects.nonNull(candidate.session())) {
                return terminateSessionCandidate(candidate);
            }
            return terminateTrackerCandidate(candidate);
        } catch (Throwable failure) {
            JdtFatalErrorPolicy.rethrowIfFatal(failure);
            log.warn("phase=jdtls-maintenance outcome=candidate-failed repoId={} exceptionType={}",
                    candidate.repositoryId().value(), failure.getClass().getSimpleName());
            return candidate.isStillLive() ? MaintenanceCandidateResult.retained()
                    : MaintenanceCandidateResult.empty();
        }
    }

    private MaintenanceCandidateResult terminateSessionCandidate(MaintenanceCandidate candidate) {
        JdtWorkspaceSession session = candidate.session();
        boolean processAttempted = session.isProcessAlive();
        JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger = candidate.kind() == MaintenanceCandidateKind.IDLE
                ? JdtWorkspaceLifecycleMetrics.EvictionTrigger.IDLE
                : JdtWorkspaceLifecycleMetrics.EvictionTrigger.MAINTENANCE_RETRY;
        Optional<JdtProcessTerminator.TerminationResult> result;
        try {
            result = session.tryStop();
        } catch (JdtWorkspaceSession.JdtProcessTerminationException exception) {
            if (processAttempted) {
                recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
                recordIdleEviction(candidate, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
            }
            throw exception;
        }
        if (result.isEmpty()) {
            recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.SKIPPED_BUSY);
            recordIdleEviction(candidate, JdtWorkspaceLifecycleMetrics.TerminationOutcome.SKIPPED_BUSY);
            return MaintenanceCandidateResult.retained();
        }
        if (processAttempted) {
            JdtWorkspaceLifecycleMetrics.TerminationOutcome outcome = terminationOutcome(result.get());
            recordTermination(trigger, outcome);
            recordIdleEviction(candidate, outcome);
        }
        removeStoppedSession(candidate.repositoryId(), session);
        return candidate.kind() == MaintenanceCandidateKind.IDLE
                ? MaintenanceCandidateResult.idleEvicted()
                : MaintenanceCandidateResult.retryConfirmed();
    }

    private MaintenanceCandidateResult terminateTrackerCandidate(MaintenanceCandidate candidate) {
        LaunchStopOutcome outcome = tryStopLaunchingProcess(
                candidate.repositoryId(), candidate.tracker(),
                JdtWorkspaceLifecycleMetrics.EvictionTrigger.MAINTENANCE_RETRY);
        return switch (outcome) {
            case CONFIRMED -> MaintenanceCandidateResult.retryConfirmed();
            case BUSY, RETAINED -> MaintenanceCandidateResult.retained();
            case NOT_TRACKED -> candidate.isStillLive()
                    ? MaintenanceCandidateResult.retained()
                    : MaintenanceCandidateResult.empty();
        };
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
                properties.getRequestTimeout(),
                ticker);
        try {
            publishSession(repositoryId, session, launchHandle.process());
            readinessProbe.awaitReady(session, client, snapshot.root());
            ensureStartupStillOwned(repositoryId, session);
        } catch (Throwable failure) {
            boolean shutdownPreemptedLiveStartup = terminated && session.isProcessAlive();
            cleanupStartup(repositoryId, session, startupFailureStatus(repositoryId, session));
            JdtFatalErrorPolicy.rethrowIfFatal(failure);
            if (shutdownPreemptedLiveStartup) {
                throw new JdtWorkspaceManagerStoppedException(repositoryId);
            }
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
        stopLaunchingProcess(repositoryId, null);
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
            stopQuietly(repositoryId, null, false);
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
                    .orElseThrow(() -> capacityReached(repositoryId));
            log.info("phase=jdtls-workspace outcome=evicted repoId={} requestedBy={}",
                    evictable.repositoryId().value(), repositoryId.value());
            stopQuietly(evictable.repositoryId(), JdtWorkspaceLifecycleMetrics.EvictionTrigger.DEMAND, true);
        }
    }

    private void removeCompletedTerminatedLaunches() {
        launchingProcesses.forEach(this::removeTerminatedLaunchingProcess);
    }

    private int trackedWorkspaceCount() {
        return sessions.size() + launchingProcesses.size();
    }

    private JdtWorkspaceCapacityException capacityReached(RepositoryId repositoryId) {
        CapacitySnapshot snapshot = capacitySnapshot();
        recordCapacityRejection(JdtWorkspaceLifecycleMetrics.CapacityRejectionReason.NO_EVICTABLE_WORKSPACE);
        return new JdtWorkspaceCapacityException(repositoryId, properties.getMaxActiveWorkspaces(), snapshot);
    }

    private CapacitySnapshot capacitySnapshot() {
        int active = 0;
        int starting = 0;
        int terminationPending = 0;
        Set<RepositoryId> accounted = new HashSet<>();
        for (Map.Entry<RepositoryId, JdtWorkspaceSession> entry : sessions.entrySet()) {
            accounted.add(entry.getKey());
            if (isTerminationPending(entry.getValue())) {
                terminationPending++;
            } else if (isStarting(entry.getValue())) {
                starting++;
            } else {
                active++;
            }
        }
        for (Map.Entry<RepositoryId, LaunchTracker> entry : launchingProcesses.entrySet()) {
            if (!accounted.add(entry.getKey())) {
                continue;
            }
            if (entry.getValue().isComplete() && entry.getValue().isProcessAlive()) {
                terminationPending++;
            } else {
                starting++;
            }
        }
        return new CapacitySnapshot(active, starting, terminationPending);
    }

    private boolean isTerminationPending(JdtWorkspaceSession session) {
        return SemanticEngineStatus.FAILED == session.status()
                || session.isInvalidated()
                || !session.isUsable();
    }

    private boolean isStarting(JdtWorkspaceSession session) {
        return SemanticEngineStatus.READY != session.status();
    }

    private JdtWorkspaceLifecycleMetrics.LifecycleSnapshot lifecycleSnapshot() {
        Map<JdtWorkspaceLifecycleMetrics.WorkspaceState, Integer> workspaces =
                new EnumMap<>(JdtWorkspaceLifecycleMetrics.WorkspaceState.class);
        Map<WorkspaceActivityKind, Integer> activities = new EnumMap<>(WorkspaceActivityKind.class);
        Set<RepositoryId> accounted = new HashSet<>();
        for (Map.Entry<RepositoryId, JdtWorkspaceSession> entry : sessions.entrySet()) {
            accounted.add(entry.getKey());
            increment(workspaces, workspaceState(entry.getValue()));
            entry.getValue().activitySnapshot().forEach((kind, count) -> activities.merge(kind, count, Integer::sum));
        }
        for (Map.Entry<RepositoryId, LaunchTracker> entry : launchingProcesses.entrySet()) {
            if (!accounted.add(entry.getKey())) {
                continue;
            }
            JdtWorkspaceLifecycleMetrics.WorkspaceState state = entry.getValue().isComplete()
                    && entry.getValue().isProcessAlive()
                    ? JdtWorkspaceLifecycleMetrics.WorkspaceState.FAILED
                    : JdtWorkspaceLifecycleMetrics.WorkspaceState.STARTING;
            increment(workspaces, state);
        }
        return new JdtWorkspaceLifecycleMetrics.LifecycleSnapshot(workspaces, activities);
    }

    private JdtWorkspaceLifecycleMetrics.WorkspaceState workspaceState(JdtWorkspaceSession session) {
        if (SemanticEngineStatus.FAILED == session.status()) {
            return JdtWorkspaceLifecycleMetrics.WorkspaceState.FAILED;
        }
        if (session.isInvalidated() || !session.isUsable()) {
            return JdtWorkspaceLifecycleMetrics.WorkspaceState.CLOSING;
        }
        if (SemanticEngineStatus.READY == session.status()) {
            return JdtWorkspaceLifecycleMetrics.WorkspaceState.READY;
        }
        return JdtWorkspaceLifecycleMetrics.WorkspaceState.STARTING;
    }

    private <T> void increment(Map<T, Integer> counts, T key) {
        counts.merge(key, 1, Integer::sum);
    }

    private Optional<JdtWorkspaceLifecycleMetrics.TerminationOutcome> stopSession(
            JdtWorkspaceSession session,
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger) {
        boolean processAttempted = session.isProcessAlive();
        try {
            JdtProcessTerminator.TerminationResult result = session.stop();
            if (!processAttempted) {
                return Optional.empty();
            }
            JdtWorkspaceLifecycleMetrics.TerminationOutcome outcome = terminationOutcome(result);
            recordTermination(trigger, outcome);
            return Optional.of(outcome);
        } catch (JdtWorkspaceSession.JdtProcessTerminationException exception) {
            if (processAttempted) {
                recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
            }
            throw exception;
        }
    }

    private JdtWorkspaceLifecycleMetrics.TerminationOutcome terminationOutcome(
            JdtProcessTerminator.TerminationResult result) {
        return result.forced()
                ? JdtWorkspaceLifecycleMetrics.TerminationOutcome.FORCED
                : JdtWorkspaceLifecycleMetrics.TerminationOutcome.GRACEFUL;
    }

    private void recordIdleEviction(
            MaintenanceCandidate candidate,
            JdtWorkspaceLifecycleMetrics.TerminationOutcome outcome) {
        if (candidate.kind() == MaintenanceCandidateKind.IDLE) {
            recordEviction(JdtWorkspaceLifecycleMetrics.EvictionTrigger.IDLE, outcome);
        }
    }

    private void recordEviction(
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger,
            JdtWorkspaceLifecycleMetrics.TerminationOutcome outcome) {
        recordMetric(() -> lifecycleMetrics.recordEviction(trigger, outcome));
    }

    private void recordTermination(
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger,
            JdtWorkspaceLifecycleMetrics.TerminationOutcome outcome) {
        if (Objects.nonNull(trigger)) {
            recordMetric(() -> lifecycleMetrics.recordTermination(trigger, outcome));
        }
    }

    private void recordTermination(
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger,
            Optional<JdtWorkspaceLifecycleMetrics.TerminationOutcome> outcome) {
        outcome.ifPresent(value -> recordTermination(trigger, value));
    }

    private void recordCapacityRejection(JdtWorkspaceLifecycleMetrics.CapacityRejectionReason reason) {
        recordMetric(() -> lifecycleMetrics.recordCapacityRejection(reason));
    }

    private void recordMetric(Runnable metricOperation) {
        try {
            metricOperation.run();
        } catch (RuntimeException exception) {
            log.debug("phase=jdtls-metrics outcome=record-failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
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

    private void stopQuietly(
            RepositoryId repositoryId,
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger,
            boolean eviction) {
        JdtWorkspaceSession session = sessions.get(repositoryId);
        if (Objects.isNull(session)) {
            return;
        }
        boolean processAttempted = session.isProcessAlive();
        try {
            Optional<JdtWorkspaceLifecycleMetrics.TerminationOutcome> outcome = stopSession(session, trigger);
            removeStoppedSession(repositoryId, session);
            if (eviction) {
                outcome.ifPresent(value -> recordEviction(trigger, value));
            }
        } catch (RuntimeException exception) {
            if (eviction && processAttempted
                    && exception instanceof JdtWorkspaceSession.JdtProcessTerminationException) {
                recordEviction(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
            }
            log.warn("phase=jdtls-workspace outcome=stop-failed repoId={} exceptionType={}",
                    repositoryId.value(), exception.getClass().getSimpleName());
        }
    }

    private void removeStoppedSession(RepositoryId repositoryId, JdtWorkspaceSession session) {
        synchronized (processRegistryLock) {
            if (session.isProcessAlive()) {
                return;
            }
            boolean removed = sessions.remove(repositoryId, session);
            if (!removed) {
                return;
            }
            transientStatuses.remove(repositoryId);
            removeGauge(repositoryId);
        }
    }

    private void stopLaunchingProcess(
            RepositoryId repositoryId,
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger) {
        LaunchTracker tracker = launchingProcesses.get(repositoryId);
        if (Objects.isNull(tracker)) {
            return;
        }
        tracker.terminationLock().lock();
        try {
            stopLaunchingProcessLocked(repositoryId, tracker, trigger);
        } finally {
            tracker.terminationLock().unlock();
        }
    }

    LaunchStopOutcome tryStopLaunchingProcess(RepositoryId repositoryId) {
        LaunchTracker tracker = launchingProcesses.get(repositoryId);
        if (Objects.isNull(tracker)) {
            return LaunchStopOutcome.NOT_TRACKED;
        }
        return tryStopLaunchingProcess(repositoryId, tracker, null);
    }

    private LaunchStopOutcome tryStopLaunchingProcess(
            RepositoryId repositoryId,
            LaunchTracker tracker,
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger) {
        if (!tracker.terminationLock().tryLock()) {
            if (Objects.nonNull(trigger)) {
                recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.SKIPPED_BUSY);
            }
            return LaunchStopOutcome.BUSY;
        }
        try {
            if (!Objects.equals(launchingProcesses.get(repositoryId), tracker)) {
                return LaunchStopOutcome.NOT_TRACKED;
            }
            return stopLaunchingProcessLocked(repositoryId, tracker, trigger);
        } finally {
            tracker.terminationLock().unlock();
        }
    }

    private LaunchStopOutcome stopLaunchingProcessLocked(
            RepositoryId repositoryId,
            LaunchTracker tracker,
            JdtWorkspaceLifecycleMetrics.EvictionTrigger trigger) {
        tracker.cancel();
        Optional<Process> process = tracker.process();
        Optional<JdtWorkspaceLifecycleMetrics.TerminationOutcome> terminationOutcome = Optional.empty();
        if (process.isPresent()) {
            JdtProcessTerminator.TerminationResult result = JdtProcessTerminator.destroyThenAwait(
                    process.get(), DEFAULT_SHUTDOWN_LOCK_WAIT);
            terminationOutcome = Optional.of(terminationOutcome(result));
            if (!result.terminated()) {
                transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
                log.error("phase=jdtls-process outcome=launch-stop-unconfirmed repoId={} failureType={}",
                        repositoryId.value(), result.failureType());
                recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
                return LaunchStopOutcome.RETAINED;
            }
        }
        launchCompletionWaitObserver.run();
        boolean launchCompleted = tracker.awaitCompletion(shutdownLockWait);
        if (!launchCompleted) {
            transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
            log.warn("phase=jdtls-process outcome=launch-cancel-pending repoId={}", repositoryId.value());
            recordTermination(trigger, terminationOutcome);
            return LaunchStopOutcome.RETAINED;
        }
        Optional<Process> completedProcess = tracker.process();
        if (completedProcess.isPresent() && completedProcess.get().isAlive()) {
            JdtProcessTerminator.TerminationResult result = JdtProcessTerminator.destroyThenAwait(
                    completedProcess.get(), DEFAULT_SHUTDOWN_LOCK_WAIT);
            terminationOutcome = Optional.of(terminationOutcome(result));
            if (!result.terminated()) {
                transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
                log.error("phase=jdtls-process outcome=launch-stop-unconfirmed repoId={} failureType={}",
                        repositoryId.value(), result.failureType());
                recordTermination(trigger, JdtWorkspaceLifecycleMetrics.TerminationOutcome.UNCONFIRMED);
                return LaunchStopOutcome.RETAINED;
            }
        }
        boolean removed = launchingProcesses.remove(repositoryId, tracker);
        if (removed) {
            transientStatuses.remove(repositoryId);
        }
        log.info("phase=jdtls-process outcome=confirmed-launch-stop repoId={}", repositoryId.value());
        recordTermination(trigger, terminationOutcome);
        return LaunchStopOutcome.CONFIRMED;
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

    /**
     * 非阻塞停止結果只描述呼叫當下擷取的 tracker 所有權。NOT_TRACKED 表示呼叫時沒有目前 tracker，
     * BUSY 表示無法取得其終止鎖，RETAINED 表示其終止尚未確認；CONFIRMED 不保證 repository 目前未被追蹤。
     */
    enum LaunchStopOutcome {
        NOT_TRACKED,
        BUSY,
        CONFIRMED,
        RETAINED
    }

    /** 容量已滿且每個工作區都有進行中請求 */
    public static final class JdtWorkspaceCapacityException extends RuntimeException {

        JdtWorkspaceCapacityException(RepositoryId repositoryId, int capacity, CapacitySnapshot snapshot) {
            super(capacityMessage(repositoryId, capacity, snapshot));
        }

        private static String capacityMessage(RepositoryId repositoryId, int capacity, CapacitySnapshot snapshot) {
            Objects.requireNonNull(repositoryId, "repositoryId is required");
            Assert.isTrue(capacity > 0, "capacity must be positive");
            CapacitySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot is required");
            return "JDT LS capacity " + capacity + " reached; no evictable workspace (active="
                    + requiredSnapshot.active() + ", starting=" + requiredSnapshot.starting()
                    + ", terminationPending=" + requiredSnapshot.terminationPending() + ")";
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

    private enum MaintenanceCandidateKind {
        IDLE,
        RETRY
    }

    private record MaintenanceCandidate(
            RepositoryId repositoryId,
            MaintenanceCandidateKind kind,
            JdtWorkspaceSession session,
            LaunchTracker tracker) {

        private static MaintenanceCandidate idleSession(
                RepositoryId repositoryId, JdtWorkspaceSession session) {
            return new MaintenanceCandidate(repositoryId, MaintenanceCandidateKind.IDLE, session, null);
        }

        private static MaintenanceCandidate retryingSession(
                RepositoryId repositoryId, JdtWorkspaceSession session) {
            return new MaintenanceCandidate(repositoryId, MaintenanceCandidateKind.RETRY, session, null);
        }

        private static MaintenanceCandidate retryingTracker(
                RepositoryId repositoryId, LaunchTracker tracker) {
            return new MaintenanceCandidate(repositoryId, MaintenanceCandidateKind.RETRY, null, tracker);
        }

        private boolean isStillLive() {
            if (Objects.nonNull(session)) {
                return session.isProcessAlive();
            }
            return tracker.isProcessAlive();
        }
    }

    private record MaintenanceCandidateResult(int confirmedRetries, int idleEvictions, int retainedProcesses) {

        private static MaintenanceCandidateResult empty() {
            return new MaintenanceCandidateResult(0, 0, 0);
        }

        private static MaintenanceCandidateResult retryConfirmed() {
            return new MaintenanceCandidateResult(1, 0, 0);
        }

        private static MaintenanceCandidateResult idleEvicted() {
            return new MaintenanceCandidateResult(0, 1, 0);
        }

        private static MaintenanceCandidateResult retained() {
            return new MaintenanceCandidateResult(0, 0, 1);
        }
    }

    private static final class LaunchTracker {

        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CountDownLatch completed = new CountDownLatch(1);
        private final ReentrantLock terminationLock = new ReentrantLock();

        private ReentrantLock terminationLock() {
            return terminationLock;
        }

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

        private boolean isProcessAlive() {
            return process().map(Process::isAlive).orElse(false);
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

record MaintenanceReport(boolean skippedBusy, int confirmedRetries, int idleEvictions, int retainedProcesses) {

    static MaintenanceReport busy() {
        return new MaintenanceReport(true, 0, 0, 0);
    }

    static MaintenanceReport empty() {
        return new MaintenanceReport(false, 0, 0, 0);
    }
}

record CapacitySnapshot(int active, int starting, int terminationPending) {

    CapacitySnapshot {
        Assert.isTrue(active >= 0, "active must not be negative");
        Assert.isTrue(starting >= 0, "starting must not be negative");
        Assert.isTrue(terminationPending >= 0, "terminationPending must not be negative");
    }
}
