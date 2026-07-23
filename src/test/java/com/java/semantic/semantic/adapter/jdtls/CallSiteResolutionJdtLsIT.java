package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.application.ExactMethodDeclarationResolver;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("jdtls-it")
class CallSiteResolutionJdtLsIT {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/call-site-resolution");
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("call-site-resolution");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("b".repeat(40));
    private static final String PACKAGE = "com.example.callsite";

    @TempDir
    Path workingTree;

    @TempDir
    Path workspaceData;

    @Test
    void should_reject_a_configured_jdtls_home_that_is_not_a_directory() {
        Path invalidHome = workingTree.resolve("missing-jdtls-home");

        assertThatThrownBy(() -> requireJdtlsHome(invalidHome.toString()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JDTLS_HOME must point at an installed JDT LS directory");
    }

    @Test
    void should_resolve_call_sites_with_exact_targets_ranges_and_definition_fallbacks() throws IOException {
        Path home = requireJdtlsHome(System.getenv("JDTLS_HOME"));
        Path root = copyFixture();
        DefaultJdtWorkspaceManager manager = manager(properties(home));
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);

        try {
            SemanticMethod exercise = resolveExactMethod(
                    service, snapshot, root, "CallSiteScenarios", "exercise", List.of());
            List<SemanticCall> calls = service.outgoingCalls(snapshot, exercise);
            List<CallScenario> scenarios = callHierarchyScenarios();

            assertThat(calls).hasSize(scenarios.size());
            assertThat(calls)
                    .extracting(this::callIdentity)
                    .containsExactlyInAnyOrderElementsOf(scenarios.stream()
                            .map(CallScenario::identity)
                            .toList());
            for (CallScenario scenario : scenarios) {
                assertCallHierarchyScenario(calls, scenario);
            }

            assertThat(uniqueCall(calls, new MethodIdentity("CallSiteScenarios", "overloaded", List.of("String")))
                    .rawSignature())
                    .isEqualTo("overloaded(String) : void");
            assertThat(uniqueCall(calls, new MethodIdentity("GenericBase", "echo", List.of("T")))
                    .rawSignature())
                    .isEqualTo("echo(T) : T");
            assertThat(uniqueCall(calls, new MethodIdentity("TextTools", "decorate", List.of("String")))
                    .rawSignature())
                    .isEqualTo("decorate(String) : String");

            SemanticMethod workerMethod = resolveExactMethod(
                    service, snapshot, root, "Worker", "work", List.of("String"));
            assertThat(service.implementations(snapshot, workerMethod))
                    .singleElement()
                    .satisfies(implementation -> {
                        assertMethod(implementation, "WorkerImpl", "work", List.of("String"));
                        assertThat(implementation.location().uri()).endsWith("/WorkerImpl.java");
                    });

            SemanticRange methodReferenceRange = range(22, 37, 22, 50);
            assertThat(calls)
                    .noneMatch(call -> SemanticResolutionOrigin.DEFINITION_FALLBACK.equals(call.origin())
                            && call.callSites().contains(methodReferenceRange))
                    .noneMatch(call -> "consume(String)".equals(call.rawSignature())
                            && call.callSites().contains(methodReferenceRange));
            assertDefinitionFallback(
                    service,
                    snapshot,
                    exercise,
                    new SemanticCallSite(methodReferenceRange, new SemanticPosition(22, 49)),
                    new MethodIdentity("CallSiteScenarios", "consume", List.of("String")),
                    "consume(String)");

            SemanticRange creationReferenceRange = range(24, 44, 24, 56);
            assertThat(uniqueCall(calls, new MethodIdentity("Created", "Created", List.of("String")))
                    .callSites())
                    .contains(creationReferenceRange);
        } finally {
            manager.shutdownAll();
        }
    }

    @Test
    void should_resolve_a_qualifier_annotated_field_to_the_matching_bean_under_real_jdt() throws IOException {
        Path home = requireJdtlsHome(System.getenv("JDTLS_HOME"));
        Path root = copyFixture();
        DefaultJdtWorkspaceManager manager = manager(properties(home));
        Lsp4jJavaSemanticService service = new Lsp4jJavaSemanticService(manager);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        SemanticCallGraphBuilder builder = new SemanticCallGraphBuilder(service, new SpringImplementationSelector());

        try {
            RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(root);
            MethodTarget routeTarget = exactTarget(syntax, "QualifierScenarios", "route", List.of("String"));
            SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, routeTarget);
            SemanticMethod route = service.resolveExactMethod(snapshot, anchor);

            OutgoingGraphFragment fragment = builder.build(snapshot, syntax, routeTarget, route, 1, 40);

            assertThat(fragment.edges())
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.resolutionStrategy())
                                .isEqualTo(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER);
                        GraphNode callee = nodeById(fragment, edge.calleeNodeId());
                        assertThat(callee.target().orElseThrow().className()).isEqualTo("FastWorker");
                        assertThat(callee.target().orElseThrow().methodName()).isEqualTo("process");
                    });
        } finally {
            manager.shutdownAll();
        }
    }

    private GraphNode nodeById(OutgoingGraphFragment fragment, CallNodeId nodeId) {
        return fragment.nodes().stream()
                .filter(node -> nodeId.equals(node.nodeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node " + nodeId));
    }

    private MethodTarget exactTarget(
            RepositorySyntax syntax, String className, String methodName, List<String> parameterTypes) {
        return syntax.classes().stream()
                .filter(metadata -> PACKAGE.equals(metadata.packageName()))
                .filter(metadata -> className.equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .filter(method -> parameterTypes.equals(method.paramTypes()))
                .flatMap(method -> method.analysisTarget().target().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing exact syntax target " + className + "#" + methodName));
    }

    private List<CallScenario> callHierarchyScenarios() {
        return List.of(
                new CallScenario(
                        new MethodIdentity("CallSiteScenarios", "overloaded", List.of("String")),
                        "overloaded(String) : void",
                        List.of(range(13, 8, 13, 27), range(14, 8, 14, 28))),
                new CallScenario(
                        new MethodIdentity("CallSiteScenarios", "overloaded", List.of("int")),
                        "overloaded(int) : void",
                        List.of(range(15, 8, 15, 21))),
                new CallScenario(
                        new MethodIdentity("Worker", "work", List.of("String")),
                        "work(String) : void",
                        List.of(range(16, 8, 16, 32), range(18, 8, 18, 38))),
                new CallScenario(
                        new MethodIdentity("GenericBase", "echo", List.of("T")),
                        "echo(T) : T",
                        List.of(range(17, 8, 17, 36))),
                new CallScenario(
                        new MethodIdentity("TextTools", "decorate", List.of("String")),
                        "decorate(String) : String",
                        List.of(range(18, 20, 18, 37))),
                new CallScenario(
                        new MethodIdentity("Chain", "open", List.of()),
                        "open() : Chain",
                        List.of(range(19, 8, 19, 20))),
                new CallScenario(
                        new MethodIdentity("Chain", "close", List.of()),
                        "close() : void",
                        List.of(range(19, 8, 19, 28))),
                new CallScenario(
                        new MethodIdentity("Created", "Created", List.of("String")),
                        "Created(String)",
                        List.of(range(20, 8, 20, 30), range(24, 44, 24, 56))),
                new CallScenario(
                        new MethodIdentity("CallSiteScenarios", "normalize", List.of("String")),
                        "normalize(String) : String",
                        List.of(range(21, 51, 21, 67))),
                new CallScenario(
                        new MethodIdentity("CallSiteScenarios", "consume", List.of("String")),
                        "consume(String) : void",
                        List.of(range(22, 37, 22, 50))));
    }

    private void assertCallHierarchyScenario(List<SemanticCall> calls, CallScenario scenario) {
        SemanticCall call = uniqueCall(calls, scenario.target());
        assertThat(call.rawSignature()).isEqualTo(scenario.rawSignature());
        assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.CALL_HIERARCHY);
        assertThat(call.callSites()).containsExactlyElementsOf(scenario.callSites());
    }

    private SemanticCall uniqueCall(List<SemanticCall> calls, MethodIdentity expected) {
        List<SemanticCall> matching = calls.stream()
                .filter(candidate -> candidate.target()
                        .map(target -> matches(target, expected))
                        .orElse(false))
                .toList();
        assertThat(matching)
                .as("expected exactly one SemanticCall for %s", expected)
                .hasSize(1);
        return matching.getFirst();
    }

    private CallIdentity callIdentity(SemanticCall call) {
        return new CallIdentity(
                call.target().map(this::methodIdentity).orElseThrow(),
                call.rawSignature(),
                call.origin());
    }

    private MethodIdentity methodIdentity(SemanticMethod method) {
        return new MethodIdentity(method.className(), method.methodName(), method.parameterTypes());
    }

    private void assertDefinitionFallback(
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            SemanticMethod caller,
            SemanticCallSite callSite,
            MethodIdentity expected,
            String rawSignature) {
        SemanticCall call = service.resolveCallResolutionAt(snapshot, caller, callSite).call()
                .orElseThrow(() -> new AssertionError("Missing definition fallback for " + expected.methodName()));
        assertThat(call.origin()).isEqualTo(SemanticResolutionOrigin.DEFINITION_FALLBACK);
        assertThat(call.rawSignature()).isEqualTo(rawSignature);
        assertThat(call.callSites()).containsExactly(callSite.range());
        SemanticMethod target = call.target()
                .orElseThrow(() -> new AssertionError("Fallback must retain the target identity"));
        assertMethod(target, expected.className(), expected.methodName(), expected.parameterTypes());
    }

    private void assertMethod(
            SemanticMethod method, String className, String methodName, List<String> parameterTypes) {
        assertThat(method.packageName()).isEqualTo(PACKAGE);
        assertThat(method.className()).isEqualTo(className);
        assertThat(method.methodName()).isEqualTo(methodName);
        assertThat(method.parameterTypes()).containsExactlyElementsOf(parameterTypes);
    }

    private boolean matches(SemanticMethod method, MethodIdentity expected) {
        return PACKAGE.equals(method.packageName())
                && expected.className().equals(method.className())
                && expected.methodName().equals(method.methodName())
                && expected.parameterTypes().equals(method.parameterTypes());
    }

    private SemanticRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SemanticRange(
                new SemanticPosition(startLine, startCharacter),
                new SemanticPosition(endLine, endCharacter));
    }

    private SemanticMethod resolveExactMethod(
            Lsp4jJavaSemanticService service,
            RepositorySnapshot snapshot,
            Path root,
            String className,
            String methodName,
            List<String> parameterTypes) {
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(root);
        MethodTarget target = syntax.classes().stream()
                .filter(metadata -> PACKAGE.equals(metadata.packageName()))
                .filter(metadata -> className.equals(metadata.className()))
                .flatMap(metadata -> metadata.methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .filter(method -> parameterTypes.equals(method.paramTypes()))
                .flatMap(method -> method.analysisTarget().target().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing exact syntax target"));
        SemanticDeclarationAnchor anchor = new ExactMethodDeclarationResolver().resolve(syntax, target);
        return service.resolveExactMethod(snapshot, anchor);
    }

    private Path requireJdtlsHome(String configuredHome) {
        assumeTrue(StringUtils.hasText(configuredHome),
                "JDTLS_HOME must be configured for real JDT LS integration tests");
        Path home = Path.of(configuredHome);
        assertThat(Files.isDirectory(home))
                .as("JDTLS_HOME must point at an installed JDT LS directory: %s", home)
                .isTrue();
        return home;
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
                Duration.ofMinutes(1),
                "2g");
    }

    private DefaultJdtWorkspaceManager manager(JdtLsProperties properties) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics lifecycleMetrics = new JdtWorkspaceLifecycleMetrics(meterRegistry);
        return new DefaultJdtWorkspaceManager(
                new JdtLsProcessFactory(properties),
                new JdtLsReadinessProbe(properties),
                properties,
                meterRegistry,
                lifecycleMetrics);
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

    private record CallScenario(
            MethodIdentity target, String rawSignature, List<SemanticRange> callSites) {

        private CallIdentity identity() {
            return new CallIdentity(target, rawSignature, SemanticResolutionOrigin.CALL_HIERARCHY);
        }
    }

    private record CallIdentity(
            MethodIdentity target, String rawSignature, SemanticResolutionOrigin origin) {
    }

    private record MethodIdentity(String className, String methodName, List<String> parameterTypes) {
    }
}
