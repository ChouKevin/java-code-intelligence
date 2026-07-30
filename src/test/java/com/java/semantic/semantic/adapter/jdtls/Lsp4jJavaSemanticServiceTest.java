package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticImplementationIssueReason;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
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
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
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
    void should_normalize_workspace_not_ready_failure_at_public_adapter_boundary() throws IOException {
        RuntimeException failure =
                new DefaultJdtWorkspaceManager.JdtWorkspaceManagerStoppedException(REPOSITORY_ID);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(failure));

        SemanticMethod method = methodAt(sourceFile("OrderService"), 1, 0);

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, method))
                .isExactlyInstanceOf(SemanticEngineNotReadyException.class)
                .hasNoCause();
    }

    @Test
    void should_convert_merge_and_sort_local_incoming_callers_and_ranges() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        String alphaUri = writeClassFile("com/example/generic/AlphaFacade.java", PACKAGE, "AlphaFacade");
        String callerUri = writeClassFile("com/example/generic/OrderFacade.java", PACKAGE, "OrderFacade");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));
        server.incomingCalls = List.of(
                incoming(callItem("submit(Order) : void", callerUri, 20, 16),
                        range(15, 12, 15, 24), range(12, 8, 12, 20)),
                incoming(callItem("submit(Order) : void", callerUri, 20, 16),
                        range(12, 8, 12, 20), range(18, 4, 18, 16)),
                incoming(callItem("submit() : void", alphaUri, 6, 12), range(6, 12, 6, 18)));
        server.documentSymbols.put(alphaUri, List.of(Either.forRight(
                classSymbol("AlphaFacade", method("submit()", " : void", 6, 12, 6, 18)))));
        server.documentSymbols.put(callerUri, List.of(Either.forRight(
                classSymbol("OrderFacade", method("submit(Order)", " : void", 20, 16, 20, 22)))));

        SemanticIncomingCallResult result = service.incomingCalls(snapshot, callee);

        assertThat(result.issues()).hasSize(0);
        assertThat(result.calls()).extracting(call -> call.caller().className())
                .containsExactly("AlphaFacade", "OrderFacade");
        assertThat(result.calls().get(1)).satisfies(call -> {
            assertThat(call.caller().className()).isEqualTo("OrderFacade");
            assertThat(call.rawSignature()).isEqualTo("submit(Order) : void");
            assertThat(call.callSites()).containsExactly(
                    semanticRange(12, 8, 12, 20),
                    semanticRange(15, 12, 15, 24),
                    semanticRange(18, 4, 18, 16));
        });
        assertThat(server.incomingItems).containsExactly(server.prepareItems.getFirst());
        assertThat(server.openedUris).containsExactly(calleeUri);
        assertThat(server.closedUris).containsExactly(calleeUri);
    }

    @Test
    void should_return_an_empty_incoming_result_for_an_empty_language_server_response() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));

        SemanticIncomingCallResult result = service.incomingCalls(snapshot, callee);

        assertThat(result).isEqualTo(SemanticIncomingCallResult.empty());
        assertThat(server.closedUris).containsExactly(calleeUri);
    }

    @Test
    void should_exclude_jdt_callers_without_an_issue() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));
        server.incomingCalls = List.of(incoming(
                callItem("libraryCaller() : void", "jdt://contents/Library.class", 3, 4),
                range(4, 1, 4, 8)));

        SemanticIncomingCallResult result = service.incomingCalls(snapshot, callee);

        assertThat(result).isEqualTo(SemanticIncomingCallResult.empty());
    }

    @Test
    void should_reject_unsafe_or_unconvertible_incoming_callers_and_keep_a_valid_sibling() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        String validUri = writeClassFile("com/example/generic/OrderFacade.java", PACKAGE, "OrderFacade");
        Path invalidSource = root.resolve("src/main/java/com/example/generic/BrokenCaller.java");
        Files.writeString(invalidSource, "package com.example.\nclass BrokenCaller { void submit() { } }\n");
        Path outside = Files.createTempFile("outside-incoming-caller", ".java");
        Files.writeString(outside, "package com.outside; class OutsideCaller { }\n");
        Path alias = root.resolve("src/main/java/com/example/generic/AliasCaller.java");
        Files.createSymbolicLink(alias, outside);
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));
        server.incomingCalls = List.of(
                incoming(callItem("valid() : void", validUri, 8, 12), range(8, 12, 8, 17)),
                incoming(callItem("broken() : void", invalidSource.toUri().toString(), 1, 26), range(1, 26, 1, 32)),
                incoming(callItem("outside() : void", outside.toUri().toString(), 1, 1), range(1, 1, 1, 8)),
                incoming(callItem("alias() : void", alias.toUri().toString(), 1, 1), range(1, 1, 1, 6)),
                incoming(callItem("unknown() : void", "https://example.invalid/caller.java", 1, 1), range(1, 1, 1, 8)));
        server.documentSymbols.put(validUri, List.of(Either.forRight(
                classSymbol("OrderFacade", method("valid()", " : void", 8, 12, 8, 17)))));
        server.documentSymbols.put(invalidSource.toUri().toString(), List.of(Either.forRight(
                classSymbol("BrokenCaller", method("broken()", " : void", 1, 26, 1, 32)))));

        SemanticIncomingCallResult result = service.incomingCalls(snapshot, callee);

        assertThat(result.calls()).singleElement().extracting(call -> call.caller().className())
                .isEqualTo("OrderFacade");
        assertThat(result.issues()).hasSize(4)
                .allSatisfy(issue -> assertThat(issue.code()).isEqualTo("CALLER_REJECTED"))
                .allSatisfy(issue -> assertThat(issue.message()).doesNotContain(
                        "outside-incoming-caller", "example.invalid", "BrokenCaller", "AliasCaller"));
        Files.deleteIfExists(outside);
    }

    @Test
    void should_return_only_issues_when_no_incoming_caller_can_be_converted() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));
        server.incomingCalls = List.of(incoming(
                callItem("unknown() : void", "https://example.invalid/caller.java", 1, 1),
                range(1, 1, 1, 8)));

        SemanticIncomingCallResult result = service.incomingCalls(snapshot, callee);

        assertThat(result.calls()).hasSize(0);
        assertThat(result.issues()).isNotEmpty();
    }

    @Test
    void should_normalize_incoming_timeout_and_protocol_failures_and_close_the_document() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));
        server.hangIncomingCalls = true;
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(session(server, Duration.ofMillis(50))));

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticRequestTimeoutException.class);
        assertThat(server.closedUris).containsExactly(calleeUri);

        server.hangIncomingCalls = false;
        server.incomingFailure = new IllegalStateException("incoming failure");

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(server.closedUris).containsExactly(calleeUri, calleeUri);
    }

    @Test
    void should_fail_when_an_exact_callee_cannot_be_prepared_and_close_the_document() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(server.closedUris).containsExactly(calleeUri);
        assertThat(server.incomingItems).hasSize(0);
    }

    @Test
    void should_reject_a_nonmatching_prepared_callee_without_an_incoming_request() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        String otherUri = sourceFile("OtherCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(callItem("cancelOrder(Invoice) : void", otherUri, 24, 8));

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(server.incomingItems).hasSize(0);
        assertThat(server.closedUris).containsExactly(calleeUri);
    }

    @Test
    void should_reject_a_prepared_callee_with_a_mismatching_selection_end() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(incomingCallItem(
                "processOrder(Order) : void", calleeUri, 10, 16, 10, 20));

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(server.incomingItems).hasSize(0);
        assertThat(server.closedUris).containsExactly(calleeUri);
    }

    @Test
    void should_reject_multiple_exactly_matching_prepared_callees() throws IOException {
        String calleeUri = sourceFile("OrderCrudService");
        SemanticMethod callee = methodAt(calleeUri, 10, 16);
        server.prepareItems = List.of(
                incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16),
                incomingCallItem("processOrder(Order) : void", calleeUri, 10, 16));

        assertThatThrownBy(() -> service.incomingCalls(snapshot, callee))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(server.incomingItems).hasSize(0);
        assertThat(server.closedUris).containsExactly(calleeUri);
    }

    @Test
    void should_prepare_call_hierarchy_at_the_syntax_proven_name_position_without_display_root_parsing()
            throws IOException {
        String uri = sourceFile("OrderCrudService");
        MethodTarget target = new MethodTarget(
                "src/main/java/com/example/generic/OrderCrudService.java",
                PACKAGE,
                "OrderCrudService",
                "processOrder",
                List.of("com.example.Order"));
        SemanticDeclarationAnchor anchor = new SemanticDeclarationAnchor(
                target, new SemanticPosition(10, 16));
        server.prepareItems = List.of(callItem("unrelated display signature", uri, 10, 16));

        SemanticMethod resolved = service.resolveExactMethod(snapshot, anchor);

        assertThat(resolved.packageName()).isEqualTo(PACKAGE);
        assertThat(resolved.className()).isEqualTo("OrderCrudService");
        assertThat(resolved.methodName()).isEqualTo("processOrder");
        assertThat(resolved.parameterTypes()).containsExactly("com.example.Order");
        assertThat(server.preparePositions).containsExactly(new Position(10, 16));
    }

    @Test
    void should_return_typed_definition_outcomes_without_choosing_the_first_target() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String alpha = writeClassFile("com/example/alpha/Target.java", "com.example.alpha", "Target");
        String beta = writeClassFile("com/example/beta/Target.java", "com.example.beta", "Target");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(18, 8, 18, 14), new SemanticPosition(18, 8));
        server.documentSymbols.put(alpha, List.of(Either.forRight(
                classSymbol("Target", method("go()", " : void", 5, 11, 5, 13)))));
        server.documentSymbols.put(beta, List.of(Either.forRight(
                classSymbol("Target", method("go()", " : void", 5, 11, 5, 13)))));

        server.definitionResponse = Either.forLeft(List.of(location(alpha, 5, 11, 5, 13)));
        SemanticCallResolution resolved = service.resolveCallResolutionAt(snapshot, caller, callSite);
        assertThat(resolved.status()).isEqualTo(SemanticCallResolutionStatus.RESOLVED);

        server.definitionResponse = Either.forLeft(List.of());
        SemanticCallResolution unresolved = service.resolveCallResolutionAt(snapshot, caller, callSite);
        assertThat(unresolved.status()).isEqualTo(SemanticCallResolutionStatus.UNRESOLVED);

        server.definitionResponse = Either.forLeft(List.of(
                location(beta, 5, 11, 5, 13), location(alpha, 5, 11, 5, 13)));
        SemanticCallResolution ambiguous = service.resolveCallResolutionAt(snapshot, caller, callSite);
        assertThat(ambiguous.status()).isEqualTo(SemanticCallResolutionStatus.AMBIGUOUS);
        assertThat(ambiguous.candidates()).extracting(method -> method.location().uri())
                .containsExactly(alpha, beta);
    }

    @Test
    void should_skip_an_unconvertible_local_definition_and_keep_scanning_for_a_resolved_sibling()
            throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        Path unconvertible = root.resolve("src/main/java/com/example/broken/Target.java");
        Files.createDirectories(unconvertible.getParent());
        Files.writeString(unconvertible, "package com.example.\nclass Target { void go() { } }\n");
        String unconvertibleUri = unconvertible.toUri().toString();
        String resolvedUri = writeClassFile("com/example/valid/Target.java", "com.example.valid", "Target");
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(18, 8, 18, 14), new SemanticPosition(18, 8));
        server.documentSymbols.put(unconvertibleUri, List.of(Either.forRight(
                classSymbol("Target", method("go()", " : void", 1, 20, 1, 22)))));
        server.documentSymbols.put(resolvedUri, List.of(Either.forRight(
                classSymbol("Target", method("go()", " : void", 4, 8, 4, 10)))));
        server.definitionResponse = Either.forLeft(List.of(
                location(unconvertibleUri, 1, 20, 1, 22), location(resolvedUri, 4, 8, 4, 10)));

        SemanticCallResolution resolution = service.resolveCallResolutionAt(snapshot, caller, callSite);

        assertThat(resolution.status()).isEqualTo(SemanticCallResolutionStatus.RESOLVED);
        assertThat(resolution.call()).get().extracting(call -> call.target().orElseThrow().location().uri())
                .isEqualTo(resolvedUri);
    }

    @Test
    void should_propagate_a_malformed_local_definition_uri_without_leaking_it() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String unsafeUri = "http://example.invalid/RESTRICTED_DEFINITION_PATH.java";
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(18, 8, 18, 14), new SemanticPosition(18, 8));
        server.definitionResponse = Either.forLeft(List.of(location(unsafeUri, 4, 8, 4, 12)));

        assertThatThrownBy(() -> service.resolveCallResolutionAt(snapshot, caller, callSite))
                .isInstanceOf(com.java.semantic.semantic.domain.SemanticProtocolException.class)
                .satisfies(exception -> assertThat(exception.toString())
                        .doesNotContain("RESTRICTED_DEFINITION_PATH", "example.invalid"));
    }

    @Test
    void should_return_an_unresolved_call_for_a_toctou_definition_source_failure() throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        Path target = root.resolve("src/main/java/com/example/race/TOCTOU_DEFINITION_PATH.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "package com.example.race; class Target { void go() { } }\n");
        String targetUri = target.toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(18, 8, 18, 14), new SemanticPosition(18, 8));
        server.definitionResponse = Either.forLeft(List.of(location(targetUri, 0, 54, 0, 56)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Target", method("go()", " : void", 0, 54, 0, 56)))));
        server.documentSymbolActions.put(targetUri, () -> deleteSource(target));

        SemanticCallResolution resolution = service.resolveCallResolutionAt(snapshot, caller, callSite);

        assertThat(resolution.status()).isEqualTo(SemanticCallResolutionStatus.UNRESOLVED);
    }

    @Test
    void should_normalize_ambiguous_candidates_to_an_immutable_total_order_and_reject_duplicates() {
        SemanticMethod later = methodAt("file:///workspace/B.java", 5, 9);
        SemanticMethod earlier = methodAt("file:///workspace/A.java", 4, 8);
        List<SemanticMethod> supplied = new ArrayList<>(List.of(later, earlier));

        SemanticCallResolution resolution = SemanticCallResolution.ambiguous(supplied);
        supplied.clear();

        assertThat(resolution.candidates()).containsExactly(earlier, later);
        assertThatThrownBy(() -> SemanticCallResolution.ambiguous(List.of(earlier, earlier)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rethrow_normalized_timeout_instead_of_marking_target_conversion_failed(
            CapturedOutput output) throws IOException {
        String callerUri = sourceFile("OrderCrudService");
        String targetPathSentinel = "SECRET_TIMEOUT_TARGET";
        String targetUri = writeClassFile(
                "com/example/secret/" + targetPathSentinel + ".java",
                "com.example.secret",
                targetPathSentinel);
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(
                callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(
                outgoing(callItem("work() : void", targetUri, 4, 8)));
        server.hangingDocumentSymbolUris.add(targetUri);
        service = new Lsp4jJavaSemanticService(
                new FakeWorkspaceManager(session(server, Duration.ofMillis(50))));
        int outputStart = output.getAll().length();

        Throwable failure = catchThrowable(() -> service.outgoingCalls(snapshot, caller));

        assertThat(failure).isExactlyInstanceOf(SemanticRequestTimeoutException.class)
                .hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(output.getAll().substring(outputStart))
                .doesNotContain(targetPathSentinel);
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
    void should_propagate_an_invalid_outgoing_target_location_without_leaking_it() throws IOException {
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

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, caller))
                .isInstanceOf(com.java.semantic.semantic.domain.SemanticProtocolException.class)
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain("RestrictedWorker"));
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
    void should_propagate_an_invalid_caller_location_without_leaking_it() {
        String callerPathSentinel = "RESTRICTED_CALLER_PATH_SENTINEL";
        String targetPathSentinel = "RESTRICTED_TARGET_PATH_SENTINEL";
        String callerUri = root.resolve(callerPathSentinel + ".java").toUri().toString();
        String targetUri = root.resolve(targetPathSentinel + ".java").toUri().toString();
        SemanticMethod caller = methodAt(callerUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", callerUri, 10, 16));
        server.outgoingCalls = List.of(outgoing(callItem("work() : void", targetUri, 4, 8)));
        server.documentSymbols.put(targetUri, List.of(Either.forRight(
                classSymbol("Target", method("work()", " : void", 4, 8, 4, 12)))));
        assertThatThrownBy(() -> service.outgoingCalls(snapshot, caller))
                .isInstanceOf(com.java.semantic.semantic.domain.SemanticProtocolException.class)
                .satisfies(exception -> assertThat(exception.toString())
                        .doesNotContain(callerPathSentinel, targetPathSentinel));
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

        Optional<SemanticCall> resolved = service.resolveCallResolutionAt(
                snapshot, caller,
                new SemanticCallSite(constructorRange, new SemanticPosition(18, 12))).call();

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

        Optional<SemanticCall> resolved = service.resolveCallResolutionAt(
                snapshot,
                caller,
                new SemanticCallSite(methodReferenceRange, new SemanticPosition(20, 14))).call();

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

        Optional<SemanticCall> resolved = service.resolveCallResolutionAt(
                snapshot, caller,
                new SemanticCallSite(constructorRange, new SemanticPosition(18, 12))).call();

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

        Optional<SemanticCall> resolved = service.resolveCallResolutionAt(
                snapshot, caller,
                new SemanticCallSite(callRange, new SemanticPosition(20, 12))).call();

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

        Optional<SemanticCall> result = service.resolveCallResolutionAt(snapshot, caller, callSite).call();

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
        Optional<SemanticCall> forward = service.resolveCallResolutionAt(snapshot, caller, callSite).call();
        server.definitionResponse = Either.forLeft(List.of(
                location(beta, 4, 8, 4, 12), location(alpha, 4, 8, 4, 12)));
        Optional<SemanticCall> reverse = service.resolveCallResolutionAt(snapshot, caller, callSite).call();

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
                        range(22, 8, 22, 23)),
                outgoing(externalCallItem(
                        "leak(jdt://opaque/private) : void", "ignored", "jdt://opaque/library/3", 4, 4)));

        List<SemanticCall> calls = service.outgoingCalls(snapshot, method);

        assertThat(calls).hasSize(3);
        assertThat(calls).allMatch(SemanticCall::external);
        assertThat(calls.getFirst().target()).isEmpty();
        assertThat(calls.getFirst().rawSignature()).isEqualTo("println(String) : void");
        assertThat(calls.getFirst().status()).isEqualTo(SemanticCallStatus.IDENTITY_UNPROVEN);
        SemanticCall incomplete = calls.get(1);
        assertThat(incomplete.target()).isEqualTo(Optional.empty());
        assertThat(incomplete.rawSignature()).isEqualTo("size() : int");
        assertThat(incomplete.callSites()).containsExactly(missingIdentitySite);
        assertThat(incomplete.external()).isTrue();
        SemanticCall unsafeDisplay = calls.getLast();
        assertThat(unsafeDisplay.target()).isEmpty();
        assertThat(unsafeDisplay.rawSignature()).isEqualTo("semantic target identity unavailable");
        assertThat(unsafeDisplay.status()).isEqualTo(SemanticCallStatus.IDENTITY_UNPROVEN);
    }

    @Test
    void should_reject_forged_external_methods_before_starting_a_workspace() {
        FakeWorkspaceManager manager = new FakeWorkspaceManager(session(server));
        service = new Lsp4jJavaSemanticService(manager);
        SemanticMethod forged = methodAt("jdt://contents/java.lang.String.class", 1, 1);
        SemanticCallSite callSite = new SemanticCallSite(
                semanticRange(2, 4, 2, 9), new SemanticPosition(2, 4));

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, forged))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThatThrownBy(() -> service.resolveCallResolutionAt(snapshot, forged, callSite))
                .isExactlyInstanceOf(SemanticProtocolException.class);
        assertThatThrownBy(() -> service.implementations(snapshot, forged))
                .isExactlyInstanceOf(SemanticProtocolException.class);

        assertThat(manager.getOrStartCalls()).isZero();
        assertThat(server.openedUris).isEmpty();
        assertThat(server.closedUris).isEmpty();
        assertThat(server.preparePositions).isEmpty();
        assertThat(server.definitionPositions).isEmpty();
    }

    @Test
    void should_invalidate_the_session_and_release_the_uri_lock_when_did_close_fails() throws IOException {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(uri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", uri, 10, 16));
        server.closeFailure = new IllegalStateException("did close failed");

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, method))
                .isExactlyInstanceOf(SemanticProtocolException.class);

        assertThat(currentSession.isInvalidated()).isTrue();
        assertThatThrownBy(() -> currentSession.withDocumentUri(uri, () -> "late lifecycle"))
                .isInstanceOf(JdtWorkspaceSession.JdtWorkspaceClosingException.class);
    }

    @Test
    void should_preserve_the_primary_query_failure_when_did_close_also_fails() throws IOException {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(uri, 10, 16);
        server.prepareFailure = new IllegalStateException("PRIMARY_QUERY_FAILURE");
        server.closeFailure = new IllegalStateException("SECRET_CLOSE_FAILURE");

        Throwable failure = catchThrowable(() -> service.outgoingCalls(snapshot, method));

        assertThat(failure).isExactlyInstanceOf(SemanticProtocolException.class);
        assertThat(failure.toString()).doesNotContain("PRIMARY_QUERY_FAILURE", "SECRET_CLOSE_FAILURE");
        assertThat(currentSession.isInvalidated()).isTrue();
    }

    @Test
    void should_preserve_fatal_query_errors_when_did_close_raises_an_error() throws IOException {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(uri, 10, 16);
        OutOfMemoryError primary = new OutOfMemoryError("PRIMARY_FATAL");
        server.prepareFailure = primary;
        server.closeFailure = new AssertionError("SECRET_CLOSE_ERROR");

        Throwable failure = catchThrowable(() -> service.outgoingCalls(snapshot, method));

        assertThat(failure).isSameAs(primary);
        assertThat(failure.getSuppressed()).singleElement()
                .satisfies(marker -> assertThat(marker.toString()).doesNotContain("SECRET_CLOSE_ERROR"));
        assertThat(currentSession.isInvalidated()).isTrue();
    }

    @Test
    void should_invalidate_the_session_when_did_close_raises_an_error() throws IOException {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(uri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", uri, 10, 16));
        server.closeFailure = new AssertionError("SECRET_CLOSE_ERROR");

        assertThatThrownBy(() -> service.outgoingCalls(snapshot, method))
                .isExactlyInstanceOf(SemanticProtocolException.class);

        assertThat(currentSession.isInvalidated()).isTrue();
    }

    @Test
    void should_serialize_same_uri_document_lifecycles_until_the_first_call_closes() throws Exception {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        SemanticMethod method = methodAt(uri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", uri, 10, 16));
        server.outgoingCallsStarted = new CountDownLatch(1);
        server.releaseOutgoingCalls = new CountDownLatch(1);
        CountDownLatch secondContendedForLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<SemanticCall>> first = executor.submit(() -> service.outgoingCalls(snapshot, method));
            assertThat(server.outgoingCallsStarted.await(1, TimeUnit.SECONDS)).isTrue();
            currentSession.setDocumentUriLockContentionObserver(uriText -> secondContendedForLock.countDown());

            Future<List<SemanticCall>> second = executor.submit(() -> service.outgoingCalls(snapshot, method));
            assertThat(secondContendedForLock.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(server.openedUris).containsExactly(uri);
            assertThat(server.lifecycleEvents).containsExactly("open", "query");

            server.releaseOutgoingCalls.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEmpty();
            assertThat(second.get(1, TimeUnit.SECONDS)).isEmpty();
            assertThat(server.lifecycleEvents).containsExactly(
                    "open", "query", "close", "open", "query", "close");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void should_serialize_real_path_aliases_under_one_canonical_document_uri() throws Exception {
        JdtWorkspaceSession currentSession = session(server);
        service = new Lsp4jJavaSemanticService(new FakeWorkspaceManager(currentSession));
        String uri = sourceFile("OrderCrudService");
        Path alias = root.resolve("src/main/java/com/example/generic/OrderCrudServiceAlias.java");
        Files.createSymbolicLink(alias, Path.of(URI.create(uri)));
        SemanticMethod canonical = methodAt(uri, 10, 16);
        SemanticMethod aliased = methodAt(alias.toUri().toString(), 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", uri, 10, 16));
        server.outgoingCallsStarted = new CountDownLatch(1);
        server.releaseOutgoingCalls = new CountDownLatch(1);
        CountDownLatch aliasContendedForLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<SemanticCall>> first = executor.submit(() -> service.outgoingCalls(snapshot, canonical));
            assertThat(server.outgoingCallsStarted.await(1, TimeUnit.SECONDS)).isTrue();
            currentSession.setDocumentUriLockContentionObserver(uriText -> aliasContendedForLock.countDown());
            Future<List<SemanticCall>> second = executor.submit(() -> service.outgoingCalls(snapshot, aliased));
            assertThat(aliasContendedForLock.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(server.lifecycleEvents).containsExactly("open", "query");
            assertThat(server.openedUris).containsExactly(uri);

            server.releaseOutgoingCalls.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEmpty();
            assertThat(second.get(1, TimeUnit.SECONDS)).isEmpty();
            assertThat(server.openedUris).containsExactly(uri, uri);
        } finally {
            executor.shutdownNow();
        }
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
    void should_log_a_safe_failure_when_the_call_hierarchy_cannot_be_prepared(
            CapturedOutput output) throws IOException {
        String sourceSentinel = "RESTRICTED_PREPARE_EMPTY_SOURCE";
        String orderUri = sourceFile(sourceSentinel);
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of();
        int outputStart = output.getAll().length();

        assertThat(service.outgoingCalls(snapshot, method)).isEmpty();
        assertThat(output.getAll().substring(outputStart))
                .contains("phase=jdtls-outgoing outcome=failed reason=call-hierarchy-not-prepared "
                        + "repoId=order-service")
                .doesNotContain(sourceSentinel);
    }

    @Test
    void should_not_log_a_failure_when_jdt_ls_returns_no_outgoing_calls(
            CapturedOutput output) throws IOException {
        String sourceSentinel = "RESTRICTED_NO_CALLS_SOURCE";
        String orderUri = sourceFile(sourceSentinel);
        SemanticMethod method = methodAt(orderUri, 10, 16);
        server.prepareItems = List.of(callItem("processOrder(Order) : void", orderUri, 10, 16));
        server.outgoingCalls = List.of();
        int outputStart = output.getAll().length();

        assertThat(service.outgoingCalls(snapshot, method)).isEmpty();
        assertThat(output.getAll().substring(outputStart))
                .doesNotContain("outcome=no-calls")
                .doesNotContain("outcome=failed")
                .doesNotContain(sourceSentinel);
    }

    @Test
    void should_preserve_a_local_conversion_issue_alongside_a_resolved_implementation() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/StripeGateway.java", PACKAGE, "StripeGateway");
        String malformedUri = writeClassFile("com/example/generic/MalformedGateway.java", PACKAGE, "MalformedGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(
                location(implUri, 8, 16, 8, 22),
                location(malformedUri, 8, 16, 8, 22)));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("StripeGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        SemanticImplementationResult implementations = service.implementations(snapshot, method);

        assertThat(implementations.methods()).singleElement().satisfies(resolved -> {
            assertThat(resolved.className()).isEqualTo("StripeGateway");
            assertThat(resolved.methodName()).isEqualTo("charge");
            assertThat(resolved.packageName()).isEqualTo(PACKAGE);
            assertThat(resolved.location().uri()).isEqualTo(implUri);
        });
        assertThat(implementations.issues()).containsExactly(SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED);
    }

    @Test
    void should_resolve_implementations_from_location_link_results() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/PaypalGateway.java", PACKAGE, "PaypalGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forRight(List.of(locationLink(implUri, 8, 16, 8, 22)));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("PaypalGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        SemanticImplementationResult implementations = service.implementations(snapshot, method);

        assertThat(implementations.methods()).extracting(SemanticMethod::className).containsExactly("PaypalGateway");
        assertThat(implementations.issues()).isEmpty();
    }

    @Test
    void should_preserve_an_external_issue_alongside_a_resolved_local_implementation() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/StripeGateway.java", PACKAGE, "StripeGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(
                location(implUri, 8, 16, 8, 22),
                new Location("jdt://contents/Library.class", range(1, 1, 1, 5))));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("StripeGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        SemanticImplementationResult implementations = service.implementations(snapshot, method);

        assertThat(implementations.methods()).extracting(SemanticMethod::className).containsExactly("StripeGateway");
        assertThat(implementations.issues()).containsExactly(SemanticImplementationIssueReason.EXTERNAL_TARGET);
    }

    @Test
    void should_deduplicate_valid_invalid_and_external_implementation_locations() throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/StripeGateway.java", PACKAGE, "StripeGateway");
        String malformedUri = writeClassFile("com/example/generic/MalformedGateway.java", PACKAGE, "MalformedGateway");
        Location valid = location(implUri, 8, 16, 8, 22);
        Location malformed = location(malformedUri, 8, 16, 8, 22);
        Location external = new Location("jdt://contents/Library.class", range(1, 1, 1, 5));
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(
                valid, malformed, external, valid, malformed, external));
        server.documentSymbols.put(implUri, List.of(Either.forRight(
                classSymbol("StripeGateway", method("charge(Order)", " : void", 8, 16, 8, 22)))));

        SemanticImplementationResult implementations = service.implementations(snapshot, method);

        assertThat(implementations.methods()).extracting(SemanticMethod::className).containsExactly("StripeGateway");
        assertThat(implementations.issues()).containsExactly(
                SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED,
                SemanticImplementationIssueReason.EXTERNAL_TARGET);
    }

    @Test
    void should_rethrow_normalized_timeout_instead_of_marking_implementation_conversion_failed()
            throws IOException {
        String interfaceUri = sourceFile("PaymentGateway");
        String implUri = writeClassFile("com/example/generic/StripeGateway.java", PACKAGE, "StripeGateway");
        SemanticMethod method = methodAt(interfaceUri, 4, 9);
        server.implementationResponse = Either.forLeft(List.of(location(implUri, 8, 16, 8, 22)));
        server.hangingDocumentSymbolUris.add(implUri);
        service = new Lsp4jJavaSemanticService(
                new FakeWorkspaceManager(session(server, Duration.ofMillis(50))));

        Throwable failure = catchThrowable(() -> service.implementations(snapshot, method));

        assertThat(failure).isExactlyInstanceOf(SemanticRequestTimeoutException.class)
                .hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
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

    private void deleteSource(Path source) {
        try {
            Files.delete(source);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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

    private CallHierarchyItem incomingCallItem(String name, String uri, int line, int character) {
        return incomingCallItem(name, uri, line, character, line, character + 5);
    }

    private CallHierarchyItem incomingCallItem(
            String name, String uri, int startLine, int startCharacter, int endLine, int endCharacter) {
        return new CallHierarchyItem(name, SymbolKind.Method, uri,
                range(startLine, 4, startLine + 2, 5),
                range(startLine, startCharacter, endLine, endCharacter));
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

    private CallHierarchyIncomingCall incoming(CallHierarchyItem from, Range... fromRanges) {
        return new CallHierarchyIncomingCall(from, List.of(fromRanges));
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
        return session(languageServer, Duration.ofSeconds(5));
    }

    private JdtWorkspaceSession session(
            FakeLanguageServer languageServer,
            Duration requestTimeout) {
        JdtLsProcessFactory.LaunchHandle handle = new JdtLsProcessFactory.LaunchHandle(
                new FakeProcess(), languageServer, new CompletableFuture<>(),
                new CompletableFuture<>(), new StderrRingBuffer(10));
        JdtWorkspaceSession session = new JdtWorkspaceSession(
                REPOSITORY_ID, REVISION, handle, requestTimeout);
        session.markReady();
        return session;
    }

    private static final class FakeWorkspaceManager implements JdtWorkspaceManager {

        private final JdtWorkspaceSession session;
        private final RuntimeException getOrStartFailure;
        private final AtomicInteger getOrStartCalls = new AtomicInteger();

        private FakeWorkspaceManager(JdtWorkspaceSession session) {
            this.session = Objects.requireNonNull(session, "session is required");
            this.getOrStartFailure = null;
        }

        private FakeWorkspaceManager(RuntimeException getOrStartFailure) {
            this.session = null;
            this.getOrStartFailure = Objects.requireNonNull(
                    getOrStartFailure, "getOrStartFailure is required");
        }

        @Override
        public JdtWorkspaceSession getOrStart(RepositorySnapshot snapshot) {
            getOrStartCalls.incrementAndGet();
            if (Objects.nonNull(getOrStartFailure)) {
                throw getOrStartFailure;
            }
            return session;
        }

        private int getOrStartCalls() {
            return getOrStartCalls.get();
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

    private static final class FakeLanguageServer implements JdtLsLanguageServer {

        private final FakeWorkspaceService workspaceService = new FakeWorkspaceService();
        private final FakeTextDocumentService textDocumentService = new FakeTextDocumentService();

        private List<WorkspaceSymbol> workspaceSymbols = List.of();
        private List<SymbolInformation> workspaceSymbolsLeft;
        private final Map<String, List<Either<SymbolInformation, DocumentSymbol>>> documentSymbols =
                new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> documentSymbolFailures = new ConcurrentHashMap<>();
        private final Map<String, Runnable> documentSymbolActions = new ConcurrentHashMap<>();
        private final Set<String> hangingDocumentSymbolUris = ConcurrentHashMap.newKeySet();
        private List<CallHierarchyItem> prepareItems = List.of();
        private List<CallHierarchyIncomingCall> incomingCalls = List.of();
        private List<CallHierarchyOutgoingCall> outgoingCalls = List.of();
        private Either<List<? extends Location>, List<? extends LocationLink>> implementationResponse =
                Either.forLeft(List.of());
        private Either<List<? extends Location>, List<? extends LocationLink>> definitionResponse =
                Either.forLeft(List.of());
        private Throwable closeFailure;
        private Throwable prepareFailure;
        private RuntimeException incomingFailure;
        private boolean hangIncomingCalls;
        private CountDownLatch outgoingCallsStarted = new CountDownLatch(0);
        private CountDownLatch releaseOutgoingCalls = new CountDownLatch(0);
        private final List<String> openedUris = Collections.synchronizedList(new ArrayList<>());
        private final List<Position> preparePositions = Collections.synchronizedList(new ArrayList<>());
        private final List<CallHierarchyItem> incomingItems = Collections.synchronizedList(new ArrayList<>());
        private final List<Position> definitionPositions = Collections.synchronizedList(new ArrayList<>());
        private final List<String> closedUris = Collections.synchronizedList(new ArrayList<>());
        private final List<String> lifecycleEvents = Collections.synchronizedList(new ArrayList<>());

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

        @Override
        public CompletableFuture<JdtLsBuildWorkspaceStatus> buildWorkspace(Boolean forceRebuild) {
            return CompletableFuture.completedFuture(JdtLsBuildWorkspaceStatus.SUCCEED);
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
                if (hangingDocumentSymbolUris.contains(uri)) {
                    return new CompletableFuture<>();
                }
                RuntimeException failure = documentSymbolFailures.get(uri);
                if (Objects.nonNull(failure)) {
                    return CompletableFuture.failedFuture(failure);
                }
                Optional.ofNullable(documentSymbolActions.get(uri)).ifPresent(Runnable::run);
                return CompletableFuture.completedFuture(documentSymbols.getOrDefault(uri, List.of()));
            }

            @Override
            public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
                    CallHierarchyPrepareParams params) {
                preparePositions.add(params.getPosition());
                if (Objects.nonNull(prepareFailure)) {
                    return CompletableFuture.failedFuture(prepareFailure);
                }
                return CompletableFuture.completedFuture(prepareItems);
            }

            @Override
            public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
                    CallHierarchyOutgoingCallsParams params) {
                lifecycleEvents.add("query");
                outgoingCallsStarted.countDown();
                try {
                    if (!releaseOutgoingCalls.await(1, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("outgoing call was not released"));
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(exception);
                }
                return CompletableFuture.completedFuture(outgoingCalls);
            }

            @Override
            public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
                    CallHierarchyIncomingCallsParams params) {
                lifecycleEvents.add("incoming-query");
                incomingItems.add(params.getItem());
                if (hangIncomingCalls) {
                    return new CompletableFuture<>();
                }
                if (Objects.nonNull(incomingFailure)) {
                    return CompletableFuture.failedFuture(incomingFailure);
                }
                return CompletableFuture.completedFuture(incomingCalls);
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
                lifecycleEvents.add("open");
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                if (closeFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                if (closeFailure instanceof Error error) {
                    throw error;
                }
                closedUris.add(params.getTextDocument().getUri());
                lifecycleEvents.add("close");
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
