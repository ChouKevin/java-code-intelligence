package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class Lsp4jJavaSemanticServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("order-service");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("a".repeat(40));
    private static final String PACKAGE = "com.example.generic";

    @TempDir
    Path root;

    private FakeLanguageServer server;
    private Lsp4jJavaSemanticService service;
    private RepositorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        server = new FakeLanguageServer();
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(session(server)));
        snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
    }

    @Test
    void should_resolve_the_exact_method_when_package_class_and_signature_match() throws IOException {
        String uri = sourceFile("OrderCrudService");
        server.workspaceSymbols = List.of(workspaceType("OrderCrudService", PACKAGE, uri));
        server.documentSymbols.put(uri, List.of(Either.forRight(
                classSymbol("OrderCrudService", method("processOrder(Order)", " : void", 10, 16, 10, 28)))));

        SemanticMethod resolved = service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "processOrder(Order)");

        assertThat(resolved.methodName()).isEqualTo("processOrder");
        assertThat(resolved.parameterTypes()).containsExactly("Order");
        assertThat(resolved.returnType()).isEqualTo("void");
        assertThat(resolved.location().uri()).isEqualTo(uri);
        assertThat(resolved.location().selectionRange().start())
                .isEqualTo(new SemanticPosition(10, 16));
    }

    @Test
    void should_resolve_a_bare_name_when_only_one_overload_exists() throws IOException {
        String uri = sourceFile("OrderCrudService");
        server.workspaceSymbols = List.of(workspaceType("OrderCrudService", PACKAGE, uri));
        server.documentSymbols.put(uri, List.of(Either.forRight(
                classSymbol("OrderCrudService", method("processOrder(Order)", " : void", 10, 16, 10, 28)))));

        SemanticMethod resolved = service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "processOrder");

        assertThat(resolved.methodName()).isEqualTo("processOrder");
        assertThat(resolved.parameterTypes()).containsExactly("Order");
    }

    @Test
    void should_throw_ambiguous_with_candidates_when_a_bare_name_has_several_overloads() throws IOException {
        String uri = sourceFile("OrderCrudService");
        server.workspaceSymbols = List.of(workspaceType("OrderCrudService", PACKAGE, uri));
        server.documentSymbols.put(uri, List.of(Either.forRight(classSymbol("OrderCrudService",
                method("save(Order)", " : void", 10, 16, 10, 20),
                method("save(Long)", " : void", 14, 16, 14, 20)))));

        SemanticAmbiguousMethodException failure = catchThrowableOfType(
                SemanticAmbiguousMethodException.class,
                () -> service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "save"));

        assertThat(failure.candidates()).containsExactlyInAnyOrder("save(Order)", "save(Long)");
    }

    @Test
    void should_match_normalized_parameter_types_ignoring_qualifiers_and_generics() throws IOException {
        String uri = sourceFile("OrderCrudService");
        server.workspaceSymbols = List.of(workspaceType("OrderCrudService", PACKAGE, uri));
        server.documentSymbols.put(uri, List.of(Either.forRight(classSymbol("OrderCrudService",
                method("save(Order)", " : void", 10, 16, 10, 20),
                method("deleteById(Long)", " : void", 14, 16, 14, 26)))));

        SemanticMethod resolved = service.resolveMethod(
                snapshot, PACKAGE, "OrderCrudService", "deleteById(java.lang.Long)");

        assertThat(resolved.methodName()).isEqualTo("deleteById");
        assertThat(resolved.parameterTypes()).containsExactly("Long");
    }

    @Test
    void should_not_match_a_same_named_method_in_a_different_package() throws IOException {
        String wanted = sourceFile("OrderCrudService");
        String other = otherFile("com/other/OrderCrudService.java");
        server.workspaceSymbols = List.of(
                workspaceType("OrderCrudService", "com.other", other),
                workspaceType("OrderCrudService", PACKAGE, wanted));
        server.documentSymbols.put(wanted, List.of(Either.forRight(
                classSymbol("OrderCrudService", method("processOrder(Order)", " : void", 10, 16, 10, 28)))));

        SemanticMethod resolved = service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "processOrder");

        assertThat(resolved.location().uri()).isEqualTo(wanted);
    }

    @Test
    void should_throw_symbol_not_found_when_the_type_is_absent() {
        server.workspaceSymbols = List.of();

        assertThatThrownBy(() -> service.resolveMethod(snapshot, PACKAGE, "Missing", "run"))
                .isInstanceOf(SemanticSymbolNotFoundException.class);
    }

    @Test
    void should_throw_symbol_not_found_when_the_method_name_is_absent() throws IOException {
        String uri = sourceFile("OrderCrudService");
        server.workspaceSymbols = List.of(workspaceType("OrderCrudService", PACKAGE, uri));
        server.documentSymbols.put(uri, List.of(Either.forRight(
                classSymbol("OrderCrudService", method("processOrder(Order)", " : void", 10, 16, 10, 28)))));

        assertThatThrownBy(() -> service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "missing"))
                .isInstanceOf(SemanticSymbolNotFoundException.class);
    }

    @Test
    void should_read_the_bare_name_from_the_signed_target_name_when_listing_outgoing_calls() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        String abstractUri = fileUri("src/main/java/com/example/generic/AbstractCrudService.java");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)),
                outgoing(callItem("deleteById(Long) : void", abstractUri, 16, 16)));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).extracting(SemanticCall::methodName).containsExactly("save", "deleteById");
        assertThat(calls.getFirst().rawSignature()).isEqualTo("save(T) : void");
        assertThat(calls).allMatch(call -> call.target().uri().equals(abstractUri));
        assertThat(calls).noneMatch(SemanticCall::external);
        assertThat(server.openedUris).containsExactly(orderUri);
        assertThat(server.closedUris).containsExactly(orderUri);
    }

    @Test
    void should_flag_jdt_and_out_of_root_targets_as_external() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("println(String) : void", "jdt://contents/System.class", 1, 1)),
                outgoing(callItem("size() : int", Path.of("/elsewhere/Other.java").toUri().toString(), 3, 3)));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).hasSize(2);
        assertThat(calls).allMatch(SemanticCall::external);
    }

    @Test
    void should_deduplicate_outgoing_calls_by_uri_and_range() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        String abstractUri = fileUri("src/main/java/com/example/generic/AbstractCrudService.java");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)),
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).hasSize(1);
    }

    @Test
    void should_throw_symbol_not_found_when_the_call_hierarchy_cannot_be_prepared() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of();

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, method))
                .isInstanceOf(SemanticSymbolNotFoundException.class);
    }

    @Test
    void should_resolve_implementations_from_location_results() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/StripeGateway.java", PACKAGE, "StripeGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(location(implUri, 8, 16, 8, 22)));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("StripeGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        List<SemanticMethod> implementations = service.implementations(snapshot, method);

        assertThat(implementations).hasSize(1);
        assertThat(implementations.getFirst().className()).isEqualTo("StripeGateway");
        assertThat(implementations.getFirst().methodName()).isEqualTo("charge");
        assertThat(implementations.getFirst().packageName()).isEqualTo(PACKAGE);
        assertThat(implementations.getFirst().location().uri()).isEqualTo(implUri);
    }

    @Test
    void should_resolve_implementations_from_location_link_results() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/PaypalGateway.java", PACKAGE, "PaypalGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forRight(List.of(locationLink(implUri, 8, 16, 8, 22)));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("PaypalGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        List<SemanticMethod> implementations = service.implementations(snapshot, method);

        assertThat(implementations).extracting(SemanticMethod::className).containsExactly("PaypalGateway");
    }

    @Test
    void should_skip_external_implementations() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(
                new Location("jdt://contents/Library.class", range(1, 1, 1, 5))));

        List<SemanticMethod> implementations = service.implementations(snapshot, method);

        assertThat(implementations).isEmpty();
    }

    @Test
    void should_read_left_and_right_workspace_symbol_responses_alike() throws IOException {
        String uri = sourceFile("OrderCrudService");
        SymbolInformation flat = new SymbolInformation(
                "OrderCrudService", SymbolKind.Class, new Location(uri, range(0, 0, 30, 0)), PACKAGE);
        server.workspaceSymbolsLeft = List.of(flat);
        server.documentSymbols.put(uri, List.of(Either.forRight(
                classSymbol("OrderCrudService", method("processOrder(Order)", " : void", 10, 16, 10, 28)))));

        SemanticMethod resolved = service.resolveMethod(snapshot, PACKAGE, "OrderCrudService", "processOrder");

        assertThat(resolved.location().uri()).isEqualTo(uri);
    }

    private SemanticMethod methodAt(String uri, int line, int character) {
        SemanticRange range = new SemanticRange(
                new SemanticPosition(line, character), new SemanticPosition(line + 2, 1));
        SemanticRange selection = new SemanticRange(
                new SemanticPosition(line, character), new SemanticPosition(line, character + 5));
        return new SemanticMethod(
                PACKAGE, "OrderCrudService", "processOrder", List.of("Order"), "void",
                new SemanticLocation(uri, range, selection));
    }

    private String sourceFile(String className) throws IOException {
        return writeClassFile("com/example/generic/" + className + ".java", PACKAGE, className);
    }

    private String writeClassFile(String relativePath, String packageName, String className) throws IOException {
        Path file = root.resolve("src/main/java").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + packageName + ";\npublic class " + className + " {}\n");
        return file.toUri().toString();
    }

    private String otherFile(String relativePath) throws IOException {
        Path file = root.resolve("src/main/java").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.other;\npublic class OrderCrudService {}\n");
        return file.toUri().toString();
    }

    private String fileUri(String relativePath) {
        return root.resolve(relativePath).toUri().toString();
    }

    private WorkspaceSymbol workspaceType(String name, String container, String uri) {
        return new WorkspaceSymbol(name, SymbolKind.Class,
                Either.forLeft(new Location(uri, range(0, 0, 30, 0))), container);
    }

    private DocumentSymbol classSymbol(String name, DocumentSymbol... methods) {
        DocumentSymbol symbol = new DocumentSymbol(
                name, SymbolKind.Class, range(0, 0, 40, 0), range(2, 13, 2, 13 + name.length()));
        symbol.setChildren(List.of(methods));
        return symbol;
    }

    private DocumentSymbol method(String name, String detail, int sl, int sc, int el, int ec) {
        DocumentSymbol symbol = new DocumentSymbol(
                name, SymbolKind.Method, range(sl, 4, el + 2, 5), range(sl, sc, el, ec));
        symbol.setDetail(detail);
        return symbol;
    }

    private CallHierarchyItem callItem(String name, String uri, int line, int character) {
        return new CallHierarchyItem(name, SymbolKind.Method, uri,
                range(line, 4, line + 2, 5), range(line, character, line, character + 4));
    }

    private CallHierarchyOutgoingCall outgoing(CallHierarchyItem to) {
        return new CallHierarchyOutgoingCall(to, List.of());
    }

    private Location location(String uri, int sl, int sc, int el, int ec) {
        return new Location(uri, range(sl, sc, el, ec));
    }

    private LocationLink locationLink(String uri, int sl, int sc, int el, int ec) {
        return new LocationLink(uri, range(sl, sc, el, ec), range(sl, sc, el, ec));
    }

    private Range range(int sl, int sc, int el, int ec) {
        return new Range(new Position(sl, sc), new Position(el, ec));
    }

    private JdtWorkspaceSession session(FakeLanguageServer languageServer) {
        JdtLsProcessFactory.LaunchHandle handle = new JdtLsProcessFactory.LaunchHandle(
                new FakeProcess(), languageServer, new CompletableFuture<>(),
                new CompletableFuture<>(), new StderrRingBuffer(10));
        JdtWorkspaceSession session = new JdtWorkspaceSession(
                REPOSITORY_ID, REVISION, handle, Duration.ofSeconds(5));
        session.markReady();
        return session;
    }

    private static final class FakeWorkspaceManager implements JdtWorkspaceManager {

        private final JdtWorkspaceSession session;

        private FakeWorkspaceManager(JdtWorkspaceSession session) {
            this.session = session;
        }

        @Override
        public JdtWorkspaceSession getOrStart(RepositorySnapshot snapshot) {
            return session;
        }

        @Override
        public SemanticEngineStatus status(RepositoryId repositoryId) {
            return SemanticEngineStatus.READY;
        }

        @Override
        public void invalidate(RepositoryId repositoryId) {
            // 測試不需要失效
        }

        @Override
        public void shutdownAll() {
            // 測試不需要關閉
        }
    }

    private static final class FakeLanguageServer implements LanguageServer {

        private final FakeWorkspaceService workspaceService = new FakeWorkspaceService();
        private final FakeTextDocumentService textDocumentService = new FakeTextDocumentService();

        private List<WorkspaceSymbol> workspaceSymbols = List.of();
        private List<SymbolInformation> workspaceSymbolsLeft;
        private final Map<String, List<Either<SymbolInformation, DocumentSymbol>>> documentSymbols =
                new ConcurrentHashMap<>();
        private List<CallHierarchyItem> prepareItems = List.of();
        private List<CallHierarchyOutgoingCall> outgoingCalls = List.of();
        private Either<List<? extends Location>, List<? extends LocationLink>> implementationResponse =
                Either.forLeft(List.of());
        private final List<String> openedUris = Collections.synchronizedList(new ArrayList<>());
        private final List<String> closedUris = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult());
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            // 測試不需要
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return textDocumentService;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return workspaceService;
        }

        private final class FakeWorkspaceService implements WorkspaceService {

            @Override
            public CompletableFuture<Either<List<? extends SymbolInformation>,
                    List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
                if (Objects.nonNull(workspaceSymbolsLeft)) {
                    return CompletableFuture.completedFuture(Either.forLeft(workspaceSymbolsLeft));
                }
                return CompletableFuture.completedFuture(Either.forRight(workspaceSymbols));
            }

            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {
                // 測試不需要
            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
                // 測試不需要
            }
        }

        private final class FakeTextDocumentService implements TextDocumentService {

            @Override
            public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
                    DocumentSymbolParams params) {
                return CompletableFuture.completedFuture(
                        documentSymbols.getOrDefault(params.getTextDocument().getUri(), List.of()));
            }

            @Override
            public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
                    CallHierarchyPrepareParams params) {
                return CompletableFuture.completedFuture(prepareItems);
            }

            @Override
            public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
                    CallHierarchyOutgoingCallsParams params) {
                return CompletableFuture.completedFuture(outgoingCalls);
            }

            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
                    implementation(ImplementationParams params) {
                return CompletableFuture.completedFuture(implementationResponse);
            }

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                openedUris.add(params.getTextDocument().getUri());
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                closedUris.add(params.getTextDocument().getUri());
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                // 測試不需要
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                // 測試不需要
            }
        }
    }

    private static final class FakeProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // 測試不需要
        }
    }
}
