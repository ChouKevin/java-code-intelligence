package com.java.semantic.semantic.adapter.jdtls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
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
import org.eclipse.lsp4j.DefinitionParams;
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
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    void should_reject_duplicate_type_uris_independently_of_workspace_response_order() throws IOException {
        String alpha = writeClassFile(
                "module-a/src/main/java/com/example/generic/Duplicate.java", PACKAGE, "Duplicate");
        String beta = writeClassFile(
                "module-b/src/main/java/com/example/generic/Duplicate.java", PACKAGE, "Duplicate");
        server.documentSymbols.put(alpha, List.of(Either.forRight(
                classSymbol("Duplicate", method("run()", " : void", 2, 9, 2, 12)))));
        server.documentSymbols.put(beta, List.of(Either.forRight(
                classSymbol("Duplicate", method("run()", " : void", 2, 9, 2, 12)))));

        for (List<WorkspaceSymbol> response : List.of(
                List.of(workspaceType("Duplicate", PACKAGE, alpha), workspaceType("Duplicate", PACKAGE, beta)),
                List.of(workspaceType("Duplicate", PACKAGE, beta), workspaceType("Duplicate", PACKAGE, alpha)))) {
            server.workspaceSymbols = response;
            assertThatThrownBy(() -> service.resolveMethod(snapshot, PACKAGE, "Duplicate", "run()"))
                    .isInstanceOf(SemanticAmbiguousTypeException.class)
                    .hasMessage("type " + PACKAGE + ".Duplicate is ambiguous");
        }
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
        String abstractUri = writeClassFile(
                "com/example/generic/AbstractCrudService.java", PACKAGE, "AbstractCrudService");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)),
                outgoing(callItem("deleteById(Long) : void", abstractUri, 16, 16)));
        server.documentSymbols.put(abstractUri, List.of(Either.forRight(classSymbol("AbstractCrudService",
                method("save(T)", " : void", 12, 16, 12, 20),
                method("deleteById(Long)", " : void", 16, 16, 16, 26)))));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).extracting(call -> call.target().orElseThrow().methodName())
                .containsExactly("save", "deleteById");
        assertThat(calls.getFirst().rawSignature()).isEqualTo("save(T) : void");
        assertThat(calls).allMatch(call -> call.target().orElseThrow().location().uri().equals(abstractUri));
        assertThat(calls).noneMatch(SemanticCall::external);
        assertThat(server.openedUris).containsExactly(orderUri);
        assertThat(server.closedUris).containsExactly(orderUri);
    }

    @Test
    void should_preserve_all_call_sites_and_resolve_complete_target_identity_when_call_repeats()
            throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = writeClassFile(
                "com/example/persistence/OrderMapper.java",
                "com.example.persistence",
                "OrderMapper");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticRange firstRange = semanticRange(12, 8, 12, 19);
        SemanticRange secondRange = semanticRange(14, 8, 14, 19);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(outgoing(
                callItem("save(Order) : void", targetUri, 8, 16),
                range(12, 8, 12, 19),
                range(12, 8, 12, 19),
                range(14, 8, 14, 19)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("OrderMapper", method("save(Order)", " : void", 8, 16, 8, 20)))));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, caller);

        assertThat(calls).singleElement().satisfies(call -> {
            SemanticMethod target = call.target().orElseThrow();
            assertThat(target.packageName()).isEqualTo("com.example.persistence");
            assertThat(target.className()).isEqualTo("OrderMapper");
            assertThat(target.methodName()).isEqualTo("save");
            assertThat(target.parameterTypes()).containsExactly("Order");
            assertThat(call.callSites()).containsExactly(firstRange, secondRange);
            assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.CALL_HIERARCHY);
        });
    }

    @Test
    void should_preserve_valid_sibling_when_an_outgoing_target_package_cannot_be_proven() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String validUri = writeClassFile(
                "com/example/generic/ValidWorker.java", PACKAGE, "ValidWorker");
        String failedUri = root.resolve("src/main/java/protected/RestrictedWorker.java").toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("work() : void", validUri, 2, 8), range(12, 4, 12, 10)),
                outgoing(callItem("restricted() : void", failedUri, 4, 8), range(13, 4, 13, 16)));
        server.documentSymbols.put(validUri, List.of(Either.forRight(
                classSymbol("ValidWorker", method("work()", " : void", 2, 8, 2, 12)))));
        server.documentSymbols.put(failedUri, List.of(Either.forRight(
                classSymbol("RestrictedWorker", method("restricted()", " : void", 4, 8, 4, 18)))));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, caller);

        assertThat(calls).hasSize(2);
        assertThat(calls).anySatisfy(call -> assertThat(call.target()).get().satisfies(target ->
                assertThat(target.className()).isEqualTo("ValidWorker")));
        assertThat(calls).anySatisfy(call -> {
            assertThat(call.target()).isEmpty();
            assertThat(call.rawSignature()).isEqualTo("semantic target identity unavailable");
            assertThat(call.status()).isEqualTo(SemanticCallStatus.CONVERSION_FAILED);
            assertThat(call.callSites()).containsExactly(semanticRange(13, 4, 13, 16));
        });
    }

    @Test
    void should_resolve_a_genuinely_default_package_target() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        Path target = root.resolve("src/main/java/DefaultWorker.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "class DefaultWorker { void work() {} }\n");
        String targetUri = target.toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(outgoing(callItem("work() : void", targetUri, 0, 27)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("DefaultWorker", method("work()", " : void", 0, 27, 0, 31)))));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, caller);

        assertThat(calls).singleElement().satisfies(call ->
                assertThat(call.target().orElseThrow().packageName()).isEmpty());
    }

    @Test
    void should_fail_closed_when_a_package_declaration_cannot_be_parsed() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        Path target = root.resolve("src/main/java/com/example/broken/Target.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                package com.example.
                class Target {
                    void work() {
                    }
                }
                """);
        String targetUri = target.toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(outgoing(callItem("work() : void", targetUri, 2, 9)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Target", method("work()", " : void", 2, 9, 2, 13)))));

        assertThat(service.outgoingCalls(snapshot, caller)).singleElement().satisfies(call -> {
            assertThat(call.target()).isEmpty();
            assertThat(call.rawSignature()).isEqualTo("semantic target identity unavailable");
            assertThat(call.status()).isEqualTo(SemanticCallStatus.CONVERSION_FAILED);
        });
    }

    @Test
    void should_fail_closed_and_log_only_safe_channels_when_source_or_package_cannot_be_read() {
        String callerPathSentinel = "RESTRICTED_CALLER_PATH_SENTINEL";
        String targetPathSentinel = "RESTRICTED_TARGET_PATH_SENTINEL";
        String callerUri = root.resolve(callerPathSentinel + ".java").toUri().toString();
        String targetUri = root.resolve(targetPathSentinel + ".java").toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(outgoing(callItem("work() : void", targetUri, 4, 8)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Target", method("work()", " : void", 4, 8, 4, 12)))));
        Logger logger = (Logger) LoggerFactory.getLogger(Lsp4jJavaSemanticService.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(service.outgoingCalls(snapshot, caller)).singleElement().satisfies(call -> {
                assertThat(call.target()).isEmpty();
                assertThat(call.rawSignature()).isEqualTo("semantic target identity unavailable");
                assertThat(call.status()).isEqualTo(SemanticCallStatus.CONVERSION_FAILED);
                assertThat(call.toString()).doesNotContain(callerPathSentinel, targetPathSentinel);
            });

            assertThat(appender.list).hasSize(3).allSatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .contains(REPOSITORY_ID.value())
                        .doesNotContain(callerPathSentinel, targetPathSentinel);
                assertThat(Arrays.toString(event.getArgumentArray()))
                        .doesNotContain(callerPathSentinel, targetPathSentinel);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("SOURCE_READ_FAILED"))
                    .anySatisfy(message -> assertThat(message).contains("PACKAGE_RESOLUTION_FAILED"))
                    .anySatisfy(message -> assertThat(message).contains("TARGET_CONVERSION_FAILED"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void should_query_constructor_definition_at_the_type_anchor_not_the_full_expression_start() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = writeClassFile(
                "com/example/domain/Order.java", "com.example.domain", "Order");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticRange constructorRange = semanticRange(18, 8, 18, 23);
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 5, 11, 5, 16)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Order", constructor("Order(String)", 5, 11, 5, 16)))));

        Optional<SemanticCall> resolved = service.resolveCallAt(
                snapshot, caller,
                new SemanticCallSite(constructorRange, new SemanticPosition(18, 12)));

        assertThat(server.definitionPositions).containsExactly(new Position(18, 12));
        assertThat(resolved).get().satisfies(call -> {
            SemanticMethod target = call.target().orElseThrow();
            assertThat(target.parameterTypes()).containsExactly("String");
            assertThat(call.callSites()).containsExactly(constructorRange);
            assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.DEFINITION_FALLBACK);
        });
    }

    @Test
    void should_query_method_reference_definition_at_name_anchor_and_retain_full_range() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = writeClassFile(
                "com/example/domain/Transformer.java", "com.example.domain", "Transformer");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticRange methodReferenceRange = semanticRange(20, 8, 20, 20);
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 7, 11, 7, 20)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Transformer", method("transform(String)", "", 7, 11, 7, 20)))));

        Optional<SemanticCall> resolved = service.resolveCallAt(
                snapshot,
                caller,
                new SemanticCallSite(methodReferenceRange, new SemanticPosition(20, 14)));

        assertThat(server.definitionPositions).containsExactly(new Position(20, 14));
        assertThat(resolved).get().satisfies(call -> {
            assertThat(call.callSites()).containsExactly(methodReferenceRange);
            assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.DEFINITION_FALLBACK);
        });
    }

    @Test
    void should_resolve_call_at_syntax_position_when_call_hierarchy_omits_constructor() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = writeClassFile(
                "com/example/domain/Order.java", "com.example.domain", "Order");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticRange constructorRange = semanticRange(18, 12, 18, 23);
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 5, 11, 5, 16)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Order", constructor("Order(String)", 5, 11, 5, 16)))));

        Optional<SemanticCall> resolved = service.resolveCallAt(
                snapshot, caller,
                new SemanticCallSite(constructorRange, new SemanticPosition(18, 12)));

        assertThat(resolved).get().satisfies(call -> {
            SemanticMethod target = call.target().orElseThrow();
            assertThat(target.packageName()).isEqualTo("com.example.domain");
            assertThat(target.className()).isEqualTo("Order");
            assertThat(target.methodName()).isEqualTo("Order");
            assertThat(target.parameterTypes()).containsExactly("String");
            assertThat(call.callSites()).containsExactly(constructorRange);
            assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.DEFINITION_FALLBACK);
        });
    }

    @Test
    void should_preserve_the_exact_selected_document_symbol_name_for_definition_fallback() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = writeClassFile(
                "com/example/domain/Transformer.java", "com.example.domain", "Transformer");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticRange callRange = semanticRange(20, 12, 20, 30);
        String selectedName = "transform(java.util.List<T>) : java.util.Map<String, T>";
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 7, 11, 7, 20)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Transformer", method(selectedName, "", 7, 11, 7, 20)))));

        Optional<SemanticCall> resolved = service.resolveCallAt(
                snapshot, caller,
                new SemanticCallSite(callRange, new SemanticPosition(20, 12)));

        assertThat(resolved).get().satisfies(call ->
                assertThat(call.rawSignature()).isEqualTo(selectedName));
    }

    @Test
    void should_treat_single_jdt_definition_without_proven_identity_as_normal_unresolved() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetUri = "jdt://contents/protected/RESTRICTED_DEFINITION_SENTINEL.class";
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(20, 8, 20, 14), new SemanticPosition(20, 8));
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 4, 8, 4, 12)));
        server.documentSymbolFailures.put(
                targetUri, new IllegalStateException("external definition must not be queried"));

        Optional<SemanticCall> result = service.resolveCallAt(snapshot, caller, callSite);

        assertThat(result).isEmpty();
    }

    @Test
    void should_reject_distinct_definition_targets_independently_of_response_order() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String alpha = writeClassFile("com/example/alpha/Target.java", "com.example.alpha", "Target");
        String beta = writeClassFile("com/example/beta/Target.java", "com.example.beta", "Target");
        server.documentSymbols.put(alpha, List.of(Either.forRight(
                classSymbol("Target", method("work()", " : void", 4, 8, 4, 12)))));
        server.documentSymbols.put(beta, List.of(Either.forRight(
                classSymbol("Target", method("work()", " : void", 4, 8, 4, 12)))));
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(20, 8, 20, 14), new SemanticPosition(20, 8));

        server.definitionResponse = Either.forLeft(List.of(
                location(alpha, 4, 8, 4, 12), location(beta, 4, 8, 4, 12)));
        Optional<SemanticCall> forward = service.resolveCallAt(snapshot, caller, callSite);
        server.definitionResponse = Either.forLeft(List.of(
                location(beta, 4, 8, 4, 12), location(alpha, 4, 8, 4, 12)));
        Optional<SemanticCall> reverse = service.resolveCallAt(snapshot, caller, callSite);

        assertThat(forward).isEmpty();
        assertThat(reverse).isEmpty();
    }

    @Test
    void should_flag_jdt_and_out_of_root_targets_as_external() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        SemanticRange missingIdentitySite = semanticRange(22, 8, 22, 23);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(externalCallItem(
                        "println(String) : void", "java.io.PrintStream", "jdt://opaque/library/1", 1, 1)),
                outgoing(callItem("size() : int", "jdt://opaque/library/2", 3, 3),
                        range(22, 8, 22, 23)));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).hasSize(2);
        assertThat(calls).allMatch(SemanticCall::external);
        assertThat(calls.getFirst().target()).get().satisfies(target -> {
            assertThat(target.packageName()).isEqualTo("java.io");
            assertThat(target.className()).isEqualTo("PrintStream");
            assertThat(target.methodName()).isEqualTo("println");
        });
        SemanticCall incomplete = calls.getLast();
        assertThat(incomplete.target()).isEqualTo(Optional.empty());
        assertThat(incomplete.rawSignature()).isEqualTo("size() : int");
        assertThat(incomplete.callSites()).containsExactly(missingIdentitySite);
        assertThat(incomplete.external()).isTrue();
    }

    @Test
    void should_deduplicate_outgoing_calls_by_uri_and_range() throws IOException {
        String orderUri = sourceFile("OrderCrudService");
        String abstractUri = writeClassFile(
                "com/example/generic/AbstractCrudService.java", PACKAGE, "AbstractCrudService");
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)),
                outgoing(callItem("save(T) : void", abstractUri, 12, 16)));
        server.documentSymbols.put(abstractUri, List.of(Either.forRight(
                classSymbol("AbstractCrudService", method("save(T)", " : void", 12, 16, 12, 20)))));

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

        assertThat(implementations).containsExactly();
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

    private DocumentSymbol constructor(String name, int sl, int sc, int el, int ec) {
        return new DocumentSymbol(
                name, SymbolKind.Constructor, range(sl, 4, el + 2, 5), range(sl, sc, el, ec));
    }

    private CallHierarchyItem callItem(String name, String uri, int line, int character) {
        return new CallHierarchyItem(name, SymbolKind.Method, uri,
                range(line, 4, line + 2, 5), range(line, character, line, character + 4));
    }

    private CallHierarchyItem externalCallItem(
            String name, String detail, String uri, int line, int character) {
        CallHierarchyItem item = callItem(name, uri, line, character);
        item.setDetail(detail);
        return item;
    }

    private CallHierarchyOutgoingCall outgoing(CallHierarchyItem to) {
        return new CallHierarchyOutgoingCall(to, List.of());
    }

    private CallHierarchyOutgoingCall outgoing(CallHierarchyItem to, Range... fromRanges) {
        return new CallHierarchyOutgoingCall(to, List.of(fromRanges));
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

    private SemanticRange semanticRange(int sl, int sc, int el, int ec) {
        return new SemanticRange(new SemanticPosition(sl, sc), new SemanticPosition(el, ec));
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
        private final Map<String, RuntimeException> documentSymbolFailures = new ConcurrentHashMap<>();
        private List<CallHierarchyItem> prepareItems = List.of();
        private List<CallHierarchyOutgoingCall> outgoingCalls = List.of();
        private Either<List<? extends Location>, List<? extends LocationLink>> implementationResponse =
                Either.forLeft(List.of());
        private Either<List<? extends Location>, List<? extends LocationLink>> definitionResponse =
                Either.forLeft(List.of());
        private final List<String> openedUris = Collections.synchronizedList(new ArrayList<>());
        private final List<Position> definitionPositions = Collections.synchronizedList(new ArrayList<>());
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
                String uri = params.getTextDocument().getUri();
                RuntimeException failure = documentSymbolFailures.get(uri);
                if (Objects.nonNull(failure)) {
                    return CompletableFuture.failedFuture(failure);
                }
                return CompletableFuture.completedFuture(documentSymbols.getOrDefault(uri, List.of()));
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
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
                    definition(DefinitionParams params) {
                definitionPositions.add(params.getPosition());
                return CompletableFuture.completedFuture(definitionResponse);
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
