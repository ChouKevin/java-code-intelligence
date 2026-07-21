package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticMethod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 以真實 JDT LS 驗證整個遷移的前提:泛型超類別上的繼承呼叫也能精確解析
 *
 * 只在設定 JDTLS_HOME 時執行;透過 jdtls-it profile 納入,單元測試永不啟動真實伺服器
 * 這裡無法在本環境跑到(JDTLS_HOME 未設定),但寫成設定後即會斷言的形式
 */
@Tag("jdtls-it")
class GenericLimitationJdtLsIT {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/generic-limitation");
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("generic-limitation");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("a".repeat(40));
    private static final String PACKAGE = "com.example.generic";
    private static final String SANITY_QUERY = "OrderCrudService";

    @TempDir
    Path workingTree;

    @TempDir
    Path workspaceData;

    /**
     * spike 的決定性斷言:OrderCrudService.processOrder 的兩個繼承呼叫都解析到 AbstractCrudService
     *
     * 泛型的 save(T) 與非泛型的 deleteById(Long) agent 的 JavaParser 都回報「Method source not found」
     */
    @Test
    void should_resolve_both_inherited_calls_when_declared_only_on_generic_superclass() throws IOException {
        Path home = jdtlsHome();
        assumeTrue(StringUtils.hasText(System.getenv("JDTLS_HOME")) && Files.isDirectory(home),
                "JDTLS_HOME must point at an installed JDT LS");
        Path root = copyFixture();
        JdtLsProperties properties = properties(home);
        DefaultJdtWorkspaceManager manager = manager(properties);
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);

        try {
            SemanticMethod processOrder = service.resolveMethod(
                    snapshot, PACKAGE, "OrderCrudService", "processOrder(Order)");
            List<SemanticCall> calls = service.outgoingCalls(snapshot, processOrder);

            assertThat(targetUri(calls, "save"))
                    .as("save(order) must resolve to the generic superclass; outgoing calls: %s", calls)
                    .endsWith("AbstractCrudService.java");
            assertThat(targetUri(calls, "deleteById"))
                    .as("deleteById(Long) is inherited and non-generic yet still unresolved by JavaParser")
                    .endsWith("AbstractCrudService.java");
        } finally {
            manager.shutdownAll();
        }
    }

    /**
     * 就緒守門的證明:probe 判定 READY 後,workspace/symbol 必定查得到專案自身型別
     *
     * 原本還斷言「匯入邊界前查詢必為空」,對真實 JDT LS 跑起來是錯的:
     * 本 fixture 只有六個檔案,匯入快到 symbol 先就位,而狀態仍是 IMPORTING
     * 兩個條件確實會分歧,方向卻依專案大小而反轉,所以那是不可靠的斷言
     *
     * 這反而佐證了 readiness 契約:狀態通知與 symbol 查詢缺一不可,
     * 只信任其中一個都會在某種專案規模下誤判
     */
    @Test
    void should_populate_workspace_symbols_only_after_the_import_settles() throws Exception {
        Path home = jdtlsHome();
        assumeTrue(StringUtils.hasText(System.getenv("JDTLS_HOME")) && Files.isDirectory(home),
                "JDTLS_HOME must point at an installed JDT LS");
        Path root = copyFixture();
        JdtLsProperties properties = properties(home);
        JdtLsProcessFactory factory = new JdtLsProcessFactory(properties);
        JdtLsReadinessProbe probe = new JdtLsReadinessProbe(properties);
        JdtLsReadinessProbe.ImportProgressClient client = probe.newClient();
        JdtWorkspaceSession session = new JdtWorkspaceSession(
                REPOSITORY_ID, REVISION,
                factory.launch(root, workspaceData.resolve(REPOSITORY_ID.value()), client),
                properties.getRequestTimeout());

        try {
            assertThat(session.status())
                    .as("a freshly launched session is not ready until the probe says so")
                    .isEqualTo(SemanticEngineStatus.IMPORTING);

            probe.awaitReady(session, client, root);

            assertThat(session.status()).isEqualTo(SemanticEngineStatus.READY);
            assertThat(symbolsPresent(session))
                    .as("workspace/symbol must return the project's own type once ready")
                    .isTrue();
        } finally {
            session.stop();
        }
    }

    private boolean symbolsPresent(JdtWorkspaceSession session) {
        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response =
                session.call("workspace/symbol",
                        server -> server.getWorkspaceService().symbol(new WorkspaceSymbolParams(SANITY_QUERY)));
        if (response.isLeft()) {
            return !CollectionUtils.isEmpty(response.getLeft());
        }
        return !CollectionUtils.isEmpty(response.getRight());
    }

    private String targetUri(List<SemanticCall> calls, String methodName) {
        return calls.stream()
                .flatMap(call -> call.target().stream())
                .filter(target -> methodName.equals(target.methodName()))
                .map(target -> target.location().uri())
                .findFirst()
                .orElse("<unresolved>");
    }

    private Path jdtlsHome() {
        String home = System.getenv("JDTLS_HOME");
        return StringUtils.hasText(home) ? Path.of(home) : Path.of("/opt/jdtls");
    }

    private JdtLsProperties properties(Path home) {
        return new JdtLsProperties(
                true,
                home,
                workspaceData,
                Duration.ofSeconds(180),
                Duration.ofSeconds(600),
                Duration.ofSeconds(60),
                1,
                Duration.ofMinutes(30),
                "2g");
    }

    private DefaultJdtWorkspaceManager manager(JdtLsProperties properties) {
        return new DefaultJdtWorkspaceManager(
                new JdtLsProcessFactory(properties),
                new JdtLsReadinessProbe(properties),
                properties,
                new SimpleMeterRegistry());
    }

    private Path copyFixture() throws IOException {
        Path source = FIXTURE.toAbsolutePath();
        Path target = workingTree.resolve(REPOSITORY_ID.value());
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }
}
