package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.domain.RepositoryId;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkDoneProgressKind;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 輪詢 JDT LS 的四項就緒條件
 *
 * ServiceReady 不等於匯入完成:spike 實測它在 3799 ms 就送出,而符號要更晚才解析得到
 * 只認狀態通知會讓每次分析都跟匯入賽跑,而且錯得無聲無息
 */
public final class JdtLsReadinessProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdtLsReadinessProbe.class);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);
    private static final String SYMBOL_OPERATION = "workspace/symbol";
    private static final String JAVA_SUFFIX = ".java";
    private static final Set<String> NON_TYPE_SOURCES = Set.of("package-info.java", "module-info.java");
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final int MAX_SCAN_DEPTH = 24;
    private static final String SERVICE_READY_STATUS = "ServiceReady";
    private static final String STARTED_STATUS = "Started";
    private static final String ERROR_STATUS = "Error";

    private final Duration importTimeout;
    private final Duration pollInterval;

    public JdtLsReadinessProbe(JdtLsProperties properties) {
        this(properties.getImportTimeout(), DEFAULT_POLL_INTERVAL);
    }

    JdtLsReadinessProbe(Duration importTimeout, Duration pollInterval) {
        this.importTimeout = Objects.requireNonNull(importTimeout, "importTimeout is required");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval is required");
    }

    /** 建立本次 session 專用、記錄匯入進度的 client */
    public ImportProgressClient newClient() {
        return new ImportProgressClient();
    }

    /**
     * 等到四項條件全部成立才標記 READY
     *
     * 1. initialize 完成(launch 回傳即代表完成)
     * 2. 匯入進度回到閒置或完成
     * 3. workspace/symbol 實際查得到專案自己的型別
     * 4. session 綁定的版本仍然有效
     */
    public void awaitReady(
            JdtWorkspaceSession session, ImportProgressClient client, Path workspaceRoot) {
        Objects.requireNonNull(session, "session is required");
        Objects.requireNonNull(client, "client is required");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot is required");
        String sanityQuery = sanityQuery(workspaceRoot).orElseThrow(() -> startupFailure(
                session, "working tree has no Java source to verify the import against", null));
        long deadlineNanos = System.nanoTime() + importTimeout.toNanos();
        while (true) {
            requireUsable(session);
            if (client.isImportSettled() && symbolQuerySucceeds(session, sanityQuery)) {
                requireUsable(session);
                if (!session.markReady()) {
                    throw startupFailure(session, "JDT LS session became unavailable while importing", null);
                }
                return;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw startupFailure(
                        session, "import did not complete within " + importTimeout, null);
            }
            pause(session);
        }
    }

    /**
     * 挑一個專案自己的型別名稱作為 sanity query
     *
     * spike 是以 fixture 自己的 OrderCrudService 判定匯入完成;沒有可用型別就無從驗證
     */
    static Optional<String> sanityQuery(Path workspaceRoot) {
        return firstTypeName(workspaceRoot.resolve(MAIN_SOURCE_ROOT))
                .or(() -> firstTypeName(workspaceRoot));
    }

    private static Optional<String> firstTypeName(Path root) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.walk(root, MAX_SCAN_DEPTH)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(JAVA_SUFFIX))
                    .filter(name -> !NON_TYPE_SOURCES.contains(name))
                    .sorted()
                    .findFirst()
                    .map(name -> name.substring(0, name.length() - JAVA_SUFFIX.length()));
        } catch (IOException exception) {
            LOGGER.warn("Scanning for a readiness probe type failed: category={} exceptionType={}",
                    "SOURCE_SCAN_FAILED", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean symbolQuerySucceeds(JdtWorkspaceSession session, String sanityQuery) {
        try {
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response =
                    session.call(SYMBOL_OPERATION, server -> server.getWorkspaceService()
                            .symbol(new WorkspaceSymbolParams(sanityQuery)));
            return hasSymbols(response);
        } catch (JdtWorkspaceSession.JdtRequestFailedException exception) {
            LOGGER.debug("Readiness symbol query failed while importing: repositoryId={} exceptionType={}",
                    session.repositoryId().value(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean hasSymbols(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response) {
        if (Objects.isNull(response)) {
            return false;
        }
        if (response.isLeft()) {
            return !CollectionUtils.isEmpty(response.getLeft());
        }
        return !CollectionUtils.isEmpty(response.getRight());
    }

    private void requireUsable(JdtWorkspaceSession session) {
        if (session.isInvalidated()) {
            throw startupFailure(session, "repository was invalidated while importing", null);
        }
        if (!session.isProcessAlive()) {
            throw startupFailure(session, "JDT LS process exited while importing", null);
        }
        if (!session.isUsable()) {
            throw startupFailure(session, "JDT LS session became unavailable while importing", null);
        }
    }

    private void pause(JdtWorkspaceSession session) {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw startupFailure(session, "readiness polling was interrupted", exception);
        }
    }

    private JdtWorkspaceStartupException startupFailure(
            JdtWorkspaceSession session, String reason, Throwable cause) {
        StderrRingBuffer stderrBuffer = session.stderrBuffer();
        String failureType = failureType(cause);
        LOGGER.warn(
                "JDT LS workspace failed: repositoryId={}, reason={}, stderrLines={}, failureType={}",
                session.repositoryId().value(), reason, stderrBuffer.lines().size(), failureType);
        return new JdtWorkspaceStartupException(
                session.repositoryId(), reason, stderrBuffer.asText(), failureType);
    }

    private static String failureType(Throwable cause) {
        return Objects.nonNull(cause) ? cause.getClass().getSimpleName() : "NONE";
    }

    /**
     * 記錄 language/status 與 $/progress 的 client
     *
     * 只記錄不判斷:Error 狀態不會讓工作區失敗,JDT LS 對可回復的問題也送 Error
     */
    public static final class ImportProgressClient implements JdtLanguageClient {

        private final Set<String> activeProgress = ConcurrentHashMap.newKeySet();
        private volatile boolean serviceReady;

        private ImportProgressClient() {
        }

        @Override
        public void languageStatus(StatusReport report) {
            if (Objects.isNull(report)) {
                return;
            }
            String statusCategory = statusCategory(report.type());
            LOGGER.debug("JDT LS status received: category={}", statusCategory);
            if (SERVICE_READY_STATUS.equalsIgnoreCase(report.type())
                    || STARTED_STATUS.equalsIgnoreCase(report.type())) {
                serviceReady = true;
            }
            if (ERROR_STATUS.equalsIgnoreCase(report.type())) {
                LOGGER.warn("JDT LS reported an error status");
            }
        }

        @Override
        public void languageEvent(Object event) {
            LOGGER.debug("JDT LS event received");
        }

        @Override
        public void notifyProgress(ProgressParams params) {
            if (Objects.isNull(params) || Objects.isNull(params.getToken())
                    || Objects.isNull(params.getValue()) || !params.getValue().isLeft()) {
                return;
            }
            WorkDoneProgressNotification notification = params.getValue().getLeft();
            if (Objects.isNull(notification) || Objects.isNull(notification.getKind())) {
                return;
            }
            String token = tokenOf(params.getToken());
            if (WorkDoneProgressKind.begin == notification.getKind()) {
                activeProgress.add(token);
            }
            if (WorkDoneProgressKind.end == notification.getKind()) {
                activeProgress.remove(token);
            }
        }

        @Override
        public void telemetryEvent(Object object) {
            // 遙測與就緒無關
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            // 編譯診斷由 Task 4 之後的查詢處理
        }

        @Override
        public void showMessage(MessageParams message) {
            LOGGER.debug("JDT LS message received: present={}", Objects.nonNull(message));
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            LOGGER.debug("JDT LS log received: present={}", Objects.nonNull(message));
        }

        private String statusCategory(String status) {
            if (SERVICE_READY_STATUS.equalsIgnoreCase(status)) {
                return "SERVICE_READY";
            }
            if (STARTED_STATUS.equalsIgnoreCase(status)) {
                return "STARTED";
            }
            if (ERROR_STATUS.equalsIgnoreCase(status)) {
                return "ERROR";
            }
            return "OTHER";
        }

        /** 收到 ServiceReady 且沒有未結束的進度回報 */
        boolean isImportSettled() {
            return serviceReady && CollectionUtils.isEmpty(activeProgress);
        }

        private String tokenOf(Either<String, Integer> token) {
            return token.isLeft() ? token.getLeft() : String.valueOf(token.getRight());
        }
    }

    /** 工作區無法就緒,附帶安全 stderr 分類 */
    public static final class JdtWorkspaceStartupException extends RuntimeException {

        private final String stderr;

        JdtWorkspaceStartupException(
                RepositoryId repositoryId, String reason, String stderr, String failureType) {
            super(message(repositoryId, reason, failureType), null, false, true);
            this.stderr = Objects.requireNonNullElse(stderr, "");
        }

        /** JDT LS 最後數行安全 stderr 分類 */
        public String stderr() {
            return stderr;
        }

        private static String message(
                RepositoryId repositoryId, String reason, String failureType) {
            String base = "semantic workspace failed for repository "
                    + repositoryId.value() + ": " + reason;
            return "NONE".equals(failureType) ? base : base + " (failureType=" + failureType + ")";
        }
    }
}
