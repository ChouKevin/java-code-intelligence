package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.CallGraphBuildResult;
import com.java.semantic.callgraph.application.CallGraphClassifier;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisMetadata;
import com.java.semantic.callgraph.domain.AnalysisStatus;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.callgraph.domain.RevisionBoundAnalysisResult;
import com.java.semantic.config.CallGraphDepthProperties;
import com.java.semantic.config.SemanticAnalysisConfiguration;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticAmbiguousTypeException;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallStatus;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticAnalysisApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision CURRENT_REVISION = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");
    private static final String SECRET_SENTINEL = "SECRET_ROOT_FAILURE_SENTINEL";
    private static final String POLICY_SENTINEL = "SECRET_POLICY_SENTINEL";

    @TempDir
    private Path repositoryRoot;

    @Mock
    private RepositoryApplicationService repositoryApplicationService;

    @Mock
    private SyntaxExtractionService syntaxExtractionService;

    @Mock
    private JavaSemanticService semanticService;

    @Mock
    private SemanticCallGraphBuilder builder;

    @Mock
    private ReadPolicy readPolicy;

    private RepositorySnapshot snapshot;
    private SemanticMethod rootMethod;
    private RepositorySyntax syntax;
    private ExplainableCallGraph graph;
    private SemanticAnalysisApplicationService service;

    @BeforeEach
    void setUp() {
        snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        rootMethod = method("OrderService", "place", List.of("Order"));
        syntax = RepositorySyntax.empty();
        graph = graph(rootMethod);
        service = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                semanticService,
                builder,
                readPolicy,
                new CallGraphDepthProperties(3));
    }

    @Test
    void should_keep_ambiguous_type_as_safe_exact_revision_failed_result() {
        String className = "SECRET_DUPLICATE_TYPE";
        SemanticAmbiguousTypeException ambiguousType =
                new SemanticAmbiguousTypeException("com.acme.secret", className);
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(
                snapshot, "com.acme.secret", className, "place(Order)"))
                .thenThrow(ambiguousType);

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID,
                Optional.of(REVISION),
                "com.acme.secret",
                className,
                "place(Order)");

        assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.data()).isNull();
        assertThat(result.analyzedRevision()).isEqualTo(REVISION.value());
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("ANALYSIS_FAILED");
            assertThat(error.message()).isEqualTo("Analysis could not be completed");
            assertThat(error.detail()).isBlank();
        });
        assertThat(result.toString())
                .doesNotContain(className, "SEMANTIC_AMBIGUOUS_METHOD");
        verifyNoInteractions(syntaxExtractionService, builder);
    }

    @Test
    void should_extract_once_and_return_exact_snapshot_revision_when_analysis_succeeds() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenReturn(rootMethod);
        when(readPolicy.visibilityOf(methodId(rootMethod))).thenReturn(EvidenceVisibility.READABLE);
        when(syntaxExtractionService.extract(repositoryRoot)).thenReturn(syntax);
        AnalysisWarning warning = new AnalysisWarning("AMBIGUOUS", "safe warning", "safe location");
        when(builder.build(snapshot, syntax, rootMethod, 3))
                .thenReturn(new CallGraphBuildResult(graph, List.of(warning), List.of(), false));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID,
                Optional.of(REVISION),
                "com.acme",
                "OrderService",
                "place(Order)");

        assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(result.data()).isSameAs(graph);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION.value());
        assertThat(result.metadata().repoId()).isEqualTo(REPOSITORY_ID.value());
        assertThat(result.warnings()).containsExactly(warning);
        verify(syntaxExtractionService, times(1)).extract(repositoryRoot);
        verify(builder).build(snapshot, syntax, rootMethod, 3);
        verify(repositoryApplicationService).withSnapshot(
                eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any());
    }

    @Test
    void should_not_resolve_root_when_repository_policy_forbids_read() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID,
                Optional.empty(),
                POLICY_SENTINEL,
                POLICY_SENTINEL,
                POLICY_SENTINEL);

        assertThat(result.status()).isEqualTo(AnalysisStatus.BUSINESS_READ_FORBIDDEN);
        assertThat(result.data()).isNull();
        assertThat(result.analyzedRevision()).isEqualTo(REVISION.value());
        assertThat(result.warnings()).extracting(AnalysisWarning::message)
                .containsExactly("Business policy prohibits reading or summarizing this target");
        assertThat(result.toString()).doesNotContain(POLICY_SENTINEL);
        verifyNoInteractions(semanticService, syntaxExtractionService, builder);
    }

    @Test
    void should_preserve_literal_fixture_revision_when_snapshot_is_a_fixture() {
        RepositorySnapshot fixtureSnapshot = new RepositorySnapshot(
                REPOSITORY_ID, repositoryRoot, RepositoryRevision.fixture());
        delegateSnapshot(fixtureSnapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(
                fixtureSnapshot, "com.acme", "OrderService", "place(Order)"))
                .thenReturn(rootMethod);
        when(readPolicy.visibilityOf(methodId(rootMethod))).thenReturn(EvidenceVisibility.READABLE);
        when(syntaxExtractionService.extract(repositoryRoot)).thenReturn(syntax);
        when(builder.build(fixtureSnapshot, syntax, rootMethod, 3))
                .thenReturn(new CallGraphBuildResult(graph, List.of(), List.of(), false));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");

        assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(result.analyzedRevision()).isEqualTo("FIXTURE");
    }

    @Test
    void should_not_extract_syntax_when_exact_root_policy_forbids_read() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme.secret", "VaultService", "read()"))
                .thenReturn(rootMethod);
        when(readPolicy.visibilityOf(methodId(rootMethod)))
                .thenReturn(EvidenceVisibility.BUSINESS_READ_FORBIDDEN);

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme.secret", "VaultService", "read()");

        assertThat(result.status()).isEqualTo(AnalysisStatus.BUSINESS_READ_FORBIDDEN);
        assertThat(result.data()).isNull();
        assertThat(result.analyzedRevision()).isEqualTo(REVISION.value());
        verifyNoInteractions(syntaxExtractionService, builder);
        InOrder order = inOrder(readPolicy, semanticService);
        order.verify(readPolicy).visibilityOfRepository(REPOSITORY_ID.value());
        order.verify(semanticService).resolveMethod(
                snapshot, "com.acme.secret", "VaultService", "read()");
        order.verify(readPolicy).visibilityOf(methodId(rootMethod));
    }

    @Test
    void should_preserve_warnings_and_errors_when_builder_reports_partial_result() {
        delegateSnapshot(snapshot);
        prepareReadableRoot();
        AnalysisWarning warning = new AnalysisWarning("CHILD_WARNING", "safe warning", "safe location");
        AnalysisError error = new AnalysisError("CHILD_FAILED", "safe failure", "");
        when(builder.build(snapshot, syntax, rootMethod, 3))
                .thenReturn(new CallGraphBuildResult(graph, List.of(warning), List.of(error), true));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");

        assertThat(result.status()).isEqualTo(AnalysisStatus.PARTIAL);
        assertThat(result.data()).isSameAs(graph);
        assertThat(result.warnings()).containsExactly(warning);
        assertThat(result.errors()).containsExactly(error);
    }

    @Test
    void should_keep_valid_sibling_and_exact_revision_when_one_target_conversion_fails() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(readPolicy.visibilityOf(any(MethodId.class))).thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenReturn(rootMethod);
        when(syntaxExtractionService.extract(repositoryRoot)).thenReturn(syntax);
        SemanticMethod validTarget = method("ValidWorker", "work", List.of());
        SemanticRange validRange = new SemanticRange(
                new SemanticPosition(10, 4), new SemanticPosition(10, 10));
        SemanticRange failedRange = new SemanticRange(
                new SemanticPosition(11, 4), new SemanticPosition(11, 16));
        SemanticCall validCall = new SemanticCall(
                Optional.of(validTarget), "work() : void", List.of(validRange), false,
                SemanticResolutionOrigin.CALL_HIERARCHY);
        SemanticCall failedCall = new SemanticCall(
                Optional.empty(), "semantic target identity unavailable", List.of(failedRange), false,
                SemanticResolutionOrigin.CALL_HIERARCHY, SemanticCallStatus.CONVERSION_FAILED);
        when(semanticService.outgoingCalls(snapshot, rootMethod))
                .thenReturn(List.of(validCall, failedCall));
        when(semanticService.outgoingCalls(snapshot, validTarget)).thenReturn(List.of());
        SemanticCallGraphBuilder integratedBuilder = new SemanticCallGraphBuilder(
                semanticService,
                mock(CallGraphClassifier.class),
                mock(SpringImplementationSelector.class),
                readPolicy);
        SemanticAnalysisApplicationService integratedService = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                semanticService,
                integratedBuilder,
                readPolicy,
                new CallGraphDepthProperties(3));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = integratedService.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");

        assertThat(result.status()).isEqualTo(AnalysisStatus.PARTIAL);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION.value());
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("CHILD_SEMANTIC_QUERY_FAILED");
            assertThat(error.detail()).isEqualTo("TARGET_CONVERSION_FAILED");
        });
        assertThat(result.data().nodes())
                .filteredOn(node -> Objects.nonNull(node.methodId()))
                .extracting(node -> node.methodId().className())
                .contains("ValidWorker");
        assertThat(result.data().edges()).extracting(CallEdge::resolutionStrategy)
                .contains(ResolutionStrategy.JDT_CALL_HIERARCHY, ResolutionStrategy.UNRESOLVED_TARGET);
    }

    @Test
    void should_return_sanitized_failed_result_when_root_resolution_fails_inside_snapshot() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenThrow(new IllegalStateException(SECRET_SENTINEL));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");

        assertSanitizedFailure(result, REVISION);
        verifyNoInteractions(syntaxExtractionService, builder);
    }

    @Test
    void should_return_sanitized_failed_result_when_syntax_extraction_fails_inside_snapshot() {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenReturn(rootMethod);
        when(readPolicy.visibilityOf(methodId(rootMethod))).thenReturn(EvidenceVisibility.READABLE);
        when(syntaxExtractionService.extract(repositoryRoot))
                .thenThrow(new IllegalStateException(SECRET_SENTINEL));

        RevisionBoundAnalysisResult<ExplainableCallGraph> result = service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");

        assertSanitizedFailure(result, REVISION);
        verify(builder, never()).build(any(), any(), any(), anyInt());
    }

    @ParameterizedTest
    @MethodSource("requestLevelFailures")
    void should_propagate_explicit_request_level_failures(RuntimeException failure) {
        delegateSnapshot(snapshot);
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.analyze(
                REPOSITORY_ID,
                Optional.of(REVISION),
                "com.acme",
                "OrderService",
                "place(Order)"))
                .isSameAs(failure);
    }

    static Stream<RuntimeException> requestLevelFailures() {
        return Stream.of(
                new SemanticAmbiguousMethodException(
                        "com.acme", "OrderService", "place", List.of("place(Order)", "place(String)")),
                new SemanticSymbolNotFoundException("secret must not reach HTTP"),
                new SemanticEngineNotReadyException(),
                new SemanticEngineStartFailedException(),
                new SemanticRequestTimeoutException(),
                new SemanticProtocolException());
    }

    @Test
    void should_propagate_revision_mismatch_instead_of_fabricating_failed_result() {
        RepositoryRevisionMismatchException mismatch =
                new RepositoryRevisionMismatchException(REVISION, CURRENT_REVISION);
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenThrow(mismatch);

        assertThatThrownBy(() -> service.analyze(
                REPOSITORY_ID,
                Optional.of(REVISION),
                "com.acme",
                "OrderService",
                "place(Order)"))
                .isSameAs(mismatch);
    }

    @Test
    void should_analyze_once_and_map_only_graph_data_when_flattened_output_is_requested() {
        SemanticAnalysisApplicationService spy = spy(service);
        RevisionBoundAnalysisResult<ExplainableCallGraph> source =
                RevisionBoundAnalysisResult.partial(
                        graph,
                        List.of(new AnalysisWarning("WARN", "safe", "")),
                        List.of(new AnalysisError("ERROR", "safe", "")),
                        new AnalysisMetadata(REPOSITORY_ID.value(), Instant.EPOCH),
                        REVISION.value());
        doReturn(source).when(spy).analyze(
                REPOSITORY_ID, Optional.of(REVISION), "com.acme", "OrderService", "place(Order)");

        RevisionBoundAnalysisResult<FlattenedCallGraph> flattened = spy.analyzeFlattened(
                REPOSITORY_ID, Optional.of(REVISION), "com.acme", "OrderService", "place(Order)");

        assertThat(flattened.data()).isEqualTo(graph.legacyFlattened());
        assertThat(flattened.status()).isEqualTo(source.status());
        assertThat(flattened.warnings()).isEqualTo(source.warnings());
        assertThat(flattened.errors()).isEqualTo(source.errors());
        assertThat(flattened.metadata()).isSameAs(source.metadata());
        assertThat(flattened.analyzedRevision()).isEqualTo(source.analyzedRevision());
        verify(spy, times(1)).analyze(
                REPOSITORY_ID, Optional.of(REVISION), "com.acme", "OrderService", "place(Order)");
    }

    @Test
    void should_preserve_repository_exception_when_snapshot_revision_is_unavailable() {
        RepositoryNotReadyException notReady = new RepositoryNotReadyException(REPOSITORY_ID);
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID),
                eq(Optional.empty()),
                any())).thenThrow(notReady);

        assertThatThrownBy(() -> service.analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)"))
                .isSameAs(notReady);

        RepositoryNotFoundException notFound = new RepositoryNotFoundException(REPOSITORY_ID);
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID),
                eq(Optional.of(REVISION)),
                any())).thenThrow(notFound);

        assertThatThrownBy(() -> service.analyze(
                REPOSITORY_ID, Optional.of(REVISION), "com.acme", "OrderService", "place(Order)"))
                .isSameAs(notFound);
    }

    @Test
    void should_register_analysis_service_when_all_collaborators_are_available() {
        new ApplicationContextRunner()
                .withBean(RepositoryApplicationService.class, () -> repositoryApplicationService)
                .withBean(SyntaxExtractionService.class, () -> syntaxExtractionService)
                .withBean(JavaSemanticService.class, () -> semanticService)
                .withUserConfiguration(SemanticAnalysisConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SemanticCallGraphBuilder.class);
                    assertThat(context).hasSingleBean(SemanticAnalysisApplicationService.class);
                });
    }

    private void prepareReadableRoot() {
        when(readPolicy.visibilityOfRepository(REPOSITORY_ID.value()))
                .thenReturn(EvidenceVisibility.READABLE);
        when(semanticService.resolveMethod(snapshot, "com.acme", "OrderService", "place(Order)"))
                .thenReturn(rootMethod);
        when(readPolicy.visibilityOf(methodId(rootMethod))).thenReturn(EvidenceVisibility.READABLE);
        when(syntaxExtractionService.extract(repositoryRoot)).thenReturn(syntax);
    }

    @SuppressWarnings("unchecked")
    private void delegateSnapshot(RepositorySnapshot repositorySnapshot) {
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID), any(), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundAnalysisResult<ExplainableCallGraph>> operation =
                            invocation.getArgument(2);
                    return operation.apply(repositorySnapshot);
                });
    }

    private static void assertSanitizedFailure(
            RevisionBoundAnalysisResult<ExplainableCallGraph> result,
            RepositoryRevision revision) {
        assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.data()).isNull();
        assertThat(result.analyzedRevision()).isEqualTo(revision.value());
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("ANALYSIS_FAILED");
            assertThat(error.message()).isEqualTo("Analysis could not be completed");
            assertThat(error.detail()).isBlank();
            assertThat(error.toString()).doesNotContain(SECRET_SENTINEL);
        });
    }

    private static SemanticMethod method(String className, String methodName, List<String> parameterTypes) {
        SemanticPosition start = new SemanticPosition(1, 0);
        SemanticPosition end = new SemanticPosition(3, 1);
        SemanticRange range = new SemanticRange(start, end);
        return new SemanticMethod(
                "com.acme",
                className,
                methodName,
                parameterTypes,
                "void",
                new SemanticLocation("file:///fixture/" + className + ".java", range, range));
    }

    private static MethodId methodId(SemanticMethod method) {
        return new MethodId(
                REPOSITORY_ID.value(),
                method.packageName(),
                method.className(),
                method.methodName(),
                method.parameterTypes());
    }

    private static ExplainableCallGraph graph(SemanticMethod method) {
        MethodId root = methodId(method);
        return new ExplainableCallGraph(
                root,
                List.of(),
                List.of(),
                Map.of(),
                new FlattenedCallGraph(List.of(), root.methodName(), Map.of()));
    }
}
