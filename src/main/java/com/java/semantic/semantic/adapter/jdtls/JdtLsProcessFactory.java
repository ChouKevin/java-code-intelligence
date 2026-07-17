package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** 建立 JDT Language Server 程序與 LSP4J 連線 */
public final class JdtLsProcessFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdtLsProcessFactory.class);
    private static final String LAUNCHER_PREFIX = "org.eclipse.equinox.launcher_";
    private static final String LAUNCHER_SUFFIX = ".jar";
    private static final long TERMINATION_TIMEOUT_MILLIS = 100;

    private final JdtLsProperties properties;
    private final ProcessStarter processStarter;
    private final ConnectionStarter connectionStarter;
    private final StderrThreadStarter stderrThreadStarter;

    /** 建立使用系統程序與標準 LSP4J launcher 的 factory */
    public JdtLsProcessFactory(JdtLsProperties properties) {
        this(properties,
                command -> new ProcessBuilder(command).start(),
                JdtLsProcessFactory::connect,
                JdtLsProcessFactory::startStderrThread);
    }

    JdtLsProcessFactory(
            JdtLsProperties properties,
            ProcessStarter processStarter,
            ConnectionStarter connectionStarter) {
        this(properties, processStarter, connectionStarter, JdtLsProcessFactory::startStderrThread);
    }

    JdtLsProcessFactory(
            JdtLsProperties properties,
            ProcessStarter processStarter,
            ConnectionStarter connectionStarter,
            StderrThreadStarter stderrThreadStarter) {
        this.properties = properties;
        this.processStarter = processStarter;
        this.connectionStarter = connectionStarter;
        this.stderrThreadStarter = stderrThreadStarter;
    }

    /** 啟動程序並完成 LSP initialize handshake */
    public LaunchHandle launch(Path workspaceRoot, Path workspaceData, JdtLanguageClient client)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Path launcherJar = findLauncherJar();
        List<String> command = createCommand(launcherJar, workspaceData);
        Process process = processStarter.start(command);
        LaunchResources resources = new LaunchResources(process);
        try {
            CompletableFuture<Void> stderrDrain = startStderrDrain(process.getErrorStream(), resources);
            Connection connection = connectionStarter.connect(client, process);
            resources.registerListener(connection.listener());
            InitializeParams initializeParams = createInitializeParams(workspaceRoot);
            connection.languageServer().initialize(initializeParams).get(
                    properties.getStartupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            connection.languageServer().initialized();
            return new LaunchHandle(
                    process,
                    connection.languageServer(),
                    connection.listener(),
                    stderrDrain);
        } catch (InterruptedException exception) {
            resources.release(exception);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            resources.release(exception);
            throw exception;
        } catch (Error error) {
            resources.release(error);
            throw error;
        }
    }

    private Path findLauncherJar() throws IOException {
        Path pluginsDirectory = properties.getHome().resolve("plugins");
        try (Stream<Path> candidates = Files.list(pluginsDirectory)) {
            List<Path> launcherJars = candidates
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(LAUNCHER_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(LAUNCHER_SUFFIX))
                    .sorted()
                    .toList();
            if (launcherJars.size() != 1) {
                throw new IOException("JDT LS launcher count must be exactly one");
            }
            return launcherJars.getFirst();
        }
    }

    private List<String> createCommand(Path launcherJar, Path workspaceData) {
        return List.of(
                "java",
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Dlog.level=ALL",
                "-Xmx" + properties.getMaxHeap(),
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", launcherJar.toString(),
                "-configuration", properties.getHome().resolve("config_linux").toString(),
                "-data", workspaceData.toString());
    }

    private InitializeParams createInitializeParams(Path workspaceRoot) {
        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        textDocument.setCallHierarchy(new CallHierarchyCapabilities());
        textDocument.setDefinition(new DefinitionCapabilities());
        textDocument.setTypeDefinition(new TypeDefinitionCapabilities());
        textDocument.setDocumentSymbol(new DocumentSymbolCapabilities());
        textDocument.setSynchronization(new SynchronizationCapabilities());

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setSymbol(new SymbolCapabilities());

        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(textDocument);
        capabilities.setWorkspace(workspace);

        InitializeParams initializeParams = new InitializeParams();
        initializeParams.setRootUri(workspaceRoot.toUri().toString());
        initializeParams.setCapabilities(capabilities);
        return initializeParams;
    }

    private CompletableFuture<Void> startStderrDrain(InputStream stderr, LaunchResources resources) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        stderrThreadStarter.start(() -> {
            try (InputStream input = stderr) {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (Throwable exception) {
                StderrDrainException drainFailure = new StderrDrainException(
                        exception.getClass().getSimpleName());
                LOGGER.error("JDT LS stderr drain failed with {}", drainFailure.failureType());
                resources.release(drainFailure);
                completion.completeExceptionally(drainFailure);
                return;
            }
            completion.complete(null);
        });
        return completion;
    }

    private static void startStderrThread(Runnable task) {
        Thread.ofPlatform()
                .name("jdtls-stderr-drain")
                .daemon(true)
                .uncaughtExceptionHandler((thread, exception) -> LOGGER.error(
                        "JDT LS stderr drain failed with {}", exception.getClass().getSimpleName()))
                .start(task);
    }

    private void releaseFailedLaunch(
            Process process,
            Optional<Future<Void>> listener,
            Throwable launchFailure) {
        boolean interrupted = attemptCleanup(
                () -> listener.ifPresent(activeListener -> activeListener.cancel(true)), launchFailure);
        interrupted |= attemptCleanup(() -> process.getOutputStream().close(), launchFailure);
        interrupted |= attemptCleanup(() -> process.getInputStream().close(), launchFailure);
        interrupted |= attemptCleanup(() -> process.getErrorStream().close(), launchFailure);
        interrupted |= attemptCleanup(process::destroy, launchFailure);
        WaitResult gracefulWait = waitForTermination(process, launchFailure);
        interrupted |= gracefulWait.interrupted();
        if (!gracefulWait.terminated()) {
            interrupted |= attemptCleanup(process::destroyForcibly, launchFailure);
            WaitResult forcibleWait = waitForTermination(process, launchFailure);
            interrupted |= forcibleWait.interrupted();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean attemptCleanup(CleanupAction action, Throwable launchFailure) {
        try {
            action.run();
            return false;
        } catch (Throwable cleanupFailure) {
            recordCleanupFailure(launchFailure, cleanupFailure);
            return cleanupFailure instanceof InterruptedException;
        }
    }

    private WaitResult waitForTermination(Process process, Throwable launchFailure) {
        try {
            boolean terminated = process.waitFor(TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return new WaitResult(terminated, false);
        } catch (Throwable cleanupFailure) {
            recordCleanupFailure(launchFailure, cleanupFailure);
            return new WaitResult(false, cleanupFailure instanceof InterruptedException);
        }
    }

    private void recordCleanupFailure(Throwable launchFailure, Throwable cleanupFailure) {
        if (cleanupFailure != launchFailure) {
            launchFailure.addSuppressed(cleanupFailure);
        }
    }

    private static Connection connect(JdtLanguageClient client, Process process) {
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client, process.getInputStream(), process.getOutputStream());
        return new Connection(launcher.getRemoteProxy(), launcher.startListening());
    }

    /** 持有已啟動程序及其通訊資源 */
    public record LaunchHandle(
            Process process,
            LanguageServer languageServer,
            Future<Void> listener,
            CompletableFuture<Void> stderrDrain) {
    }

    /** 表示已清除資源的 stderr drain 失敗 */
    public static final class StderrDrainException extends RuntimeException {

        private final String failureType;

        private StderrDrainException(String failureType) {
            super("JDT LS stderr drain failed with " + failureType, null, false, true);
            this.failureType = failureType;
        }

        /** 回傳不含 stderr payload 的失敗類型 */
        public String failureType() {
            return failureType;
        }
    }

    record Connection(LanguageServer languageServer, Future<Void> listener) {
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface ConnectionStarter {

        Connection connect(JdtLanguageClient client, Process process);
    }

    @FunctionalInterface
    interface StderrThreadStarter {

        void start(Runnable task);
    }

    @FunctionalInterface
    private interface CleanupAction {

        void run() throws Exception;
    }

    private record WaitResult(boolean terminated, boolean interrupted) {
    }

    private final class LaunchResources {

        private final Process process;
        private Optional<Future<Void>> listener = Optional.empty();
        private Optional<StderrDrainException> stderrFailure = Optional.empty();
        private boolean released;

        private LaunchResources(Process process) {
            this.process = process;
        }

        private synchronized void registerListener(Future<Void> listener) {
            this.listener = Optional.of(listener);
            stderrFailure.ifPresent(failure -> {
                attemptCleanup(() -> listener.cancel(true), failure);
                throw failure;
            });
        }

        private synchronized void release(Throwable failure) {
            if (released) {
                return;
            }
            released = true;
            if (failure instanceof StderrDrainException stderrDrainFailure) {
                stderrFailure = Optional.of(stderrDrainFailure);
            }
            releaseFailedLaunch(process, listener, failure);
        }
    }
}
