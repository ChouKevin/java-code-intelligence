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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每個活躍儲存庫一個 JDT LS 程序
 *
 * 每個工作區約 1 GB RSS,因此啟動彼此互斥,且淘汰只挑沒有進行中請求的 session
 */
@Service
public class DefaultJdtWorkspaceManager implements JdtWorkspaceManager, RepositoryMutationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultJdtWorkspaceManager.class);
    private static final String PEAK_RSS_METRIC = "jdtls.workspace.peak.rss.kilobytes";
    private static final String REPOSITORY_TAG = "repository";
    private static final Duration DEFAULT_SHUTDOWN_LOCK_WAIT = Duration.ofSeconds(5);

    private final JdtLsProcessFactory processFactory;
    private final JdtLsReadinessProbe readinessProbe;
    private final JdtLsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<RepositoryId, JdtWorkspaceSession> sessions = new ConcurrentHashMap<>();
    private final Map<RepositoryId, SemanticEngineStatus> transientStatuses = new ConcurrentHashMap<>();
    private final Map<RepositoryId, Meter.Id> gauges = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
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
            JdtWorkspaceSession session = sessions.remove(repositoryId);
            transientStatuses.remove(repositoryId);
            removeGauge(repositoryId);
            if (Objects.isNull(session)) {
                return;
            }
            session.invalidate();
            session.stop();
            LOGGER.info("JDT LS workspace invalidated: repositoryId={}", repositoryId.value());
        } catch (RuntimeException exception) {
            LOGGER.warn("JDT LS workspace invalidation failed: repositoryId={}",
                    repositoryId.value(), exception);
            throw new RepositoryMutationException("semantic workspace invalidation failed", exception);
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
        terminated = true;
        boolean acquired = acquireLifecycleLock();
        try {
            for (RepositoryId repositoryId : Set.copyOf(sessions.keySet())) {
                stopQuietly(repositoryId);
            }
            transientStatuses.clear();
        } finally {
            if (acquired) {
                lifecycleLock.unlock();
            }
        }
    }

    private boolean acquireLifecycleLock() {
        try {
            return lifecycleLock.tryLock(shutdownLockWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while acquiring the lifecycle lock during shutdown");
            return false;
        }
    }

    private Optional<JdtWorkspaceSession> runningSession(RepositorySnapshot snapshot) {
        JdtWorkspaceSession session = sessions.get(snapshot.repositoryId());
        if (Objects.isNull(session)
                || SemanticEngineStatus.READY != session.status()
                || session.isInvalidated()
                || !session.revision().equals(snapshot.revision())) {
            return Optional.empty();
        }
        session.touch();
        return Optional.of(session);
    }

    private JdtWorkspaceSession startLocked(RepositorySnapshot snapshot) {
        RepositoryId repositoryId = snapshot.repositoryId();
        discardUnusable(repositoryId);
        makeRoomFor(repositoryId);
        transientStatuses.put(repositoryId, SemanticEngineStatus.STARTING);
        JdtLsReadinessProbe.ImportProgressClient client = readinessProbe.newClient();
        JdtWorkspaceSession session = new JdtWorkspaceSession(
                repositoryId,
                snapshot.revision(),
                launch(snapshot, client),
                properties.getRequestTimeout());
        sessions.put(repositoryId, session);
        transientStatuses.remove(repositoryId);
        registerPeakRssGauge(session);
        try {
            readinessProbe.awaitReady(session, client, snapshot.root());
        } catch (RuntimeException exception) {
            sessions.remove(repositoryId);
            removeGauge(repositoryId);
            stopAndPreserve(session, exception);
            transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
            throw exception;
        }
        LOGGER.info("JDT LS workspace ready: repositoryId={}, revision={}",
                repositoryId.value(), snapshot.revision().value());
        return session;
    }

    private JdtLsProcessFactory.LaunchHandle launch(
            RepositorySnapshot snapshot, JdtLsReadinessProbe.ImportProgressClient client) {
        RepositoryId repositoryId = snapshot.repositoryId();
        Path workspaceData = properties.getWorkspaceDataRoot().resolve(repositoryId.value());
        try {
            return processFactory.launch(snapshot.root(), workspaceData, client);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw launchFailure(repositoryId, "JDT LS launch was interrupted", exception);
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException exception) {
            throw launchFailure(repositoryId, "JDT LS launch failed", exception);
        }
    }

    private JdtLsReadinessProbe.JdtWorkspaceStartupException launchFailure(
            RepositoryId repositoryId, String reason, Throwable cause) {
        transientStatuses.put(repositoryId, SemanticEngineStatus.FAILED);
        LOGGER.warn("JDT LS workspace failed: repositoryId={}, reason={}",
                repositoryId.value(), reason, cause);
        return new JdtLsReadinessProbe.JdtWorkspaceStartupException(repositoryId, reason, "", cause);
    }

    private void discardUnusable(RepositoryId repositoryId) {
        if (sessions.containsKey(repositoryId)) {
            LOGGER.info("Discarding unusable JDT LS workspace: repositoryId={}", repositoryId.value());
            stopQuietly(repositoryId);
        }
    }

    /**
     * 容量已滿時淘汰最久未用且沒有進行中請求的 session
     *
     * 用 tryBeginEviction 原子交握取代「先讀 activeRequests 再停止」的檢查後動作:
     * 兩步之間若有請求落地,舊寫法會把有進行中請求的 session 拆掉,讓呼叫端等到逾時
     */
    private void makeRoomFor(RepositoryId repositoryId) {
        while (sessions.size() >= properties.getMaxActiveWorkspaces()) {
            JdtWorkspaceSession evictable = sessions.values().stream()
                    .sorted(Comparator.comparingLong(JdtWorkspaceSession::lastUsedNanos))
                    .filter(JdtWorkspaceSession::tryBeginEviction)
                    .findFirst()
                    .orElseThrow(() -> new JdtWorkspaceCapacityException(repositoryId));
            LOGGER.info("Evicting idle JDT LS workspace: repositoryId={}, requestedBy={}",
                    evictable.repositoryId().value(), repositoryId.value());
            stopQuietly(evictable.repositoryId());
        }
    }

    /**
     * 清理失敗的啟動,但保留原始失敗
     *
     * stop 失敗不可以蓋掉啟動失敗:附在 stderr 上的診斷正是這個 task 存在的理由
     */
    private void stopAndPreserve(JdtWorkspaceSession session, RuntimeException startupFailure) {
        try {
            session.stop();
        } catch (RuntimeException stopFailure) {
            LOGGER.warn("Stopping a failed JDT LS workspace failed: repositoryId={}",
                    session.repositoryId().value(), stopFailure);
            startupFailure.addSuppressed(stopFailure);
        }
    }

    private void stopQuietly(RepositoryId repositoryId) {
        JdtWorkspaceSession session = sessions.remove(repositoryId);
        transientStatuses.remove(repositoryId);
        removeGauge(repositoryId);
        if (Objects.isNull(session)) {
            return;
        }
        try {
            session.stop();
        } catch (RuntimeException exception) {
            LOGGER.warn("Stopping JDT LS workspace failed: repositoryId={}",
                    repositoryId.value(), exception);
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
}
