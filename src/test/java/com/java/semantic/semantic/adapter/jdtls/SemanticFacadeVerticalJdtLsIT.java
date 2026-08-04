package com.java.semantic.semantic.adapter.jdtls;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 透過真實程序與驗證 HTTP 證明 revision 鎖定的方向性語意 facade */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("jdtls-it")
@Import(SemanticFacadeVerticalJdtLsIT.LifecycleTestConfiguration.class)
class SemanticFacadeVerticalJdtLsIT {

    private static final String TOKEN = "semantic-facade-vertical-token";
    private static final String FIXED_REPOSITORY = "fixed-system-agent";
    private static final String FIXTURE_REPOSITORY = "semantic-vertical";
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/semantic-facade-vertical")
            .toAbsolutePath().normalize();
    private static final Path WORKSPACE_DATA = Path.of("target/semantic-facade-vertical-jdtls")
            .toAbsolutePath().normalize();
    private static final MutableTicker LIFECYCLE_TICKER = new MutableTicker();
    private static CurrentProjectRemoteFixture remote;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DefaultJdtWorkspaceManager workspaceManager;

    @Autowired
    private JdtWorkspaceIdleReaper workspaceIdleReaper;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledAnnotationBeanPostProcessor;

    @DynamicPropertySource
    static void semanticFacadeProperties(DynamicPropertyRegistry registry) {
        registry.add("semantic.api.api-token", () -> TOKEN);
        registry.add("semantic.data-root", () -> WORKSPACE_DATA.resolve("repositories").toString());
        registry.add("semantic.jdtls.home", () -> requireJdtlsHome().toString());
        registry.add("semantic.jdtls.workspace-data-root", () -> WORKSPACE_DATA.resolve("workspaces").toString());
        registry.add("semantic.jdtls.max-active-workspaces", () -> "2");
        registry.add("semantic.jdtls.maintenance-interval", () -> "24h");
        registry.add("semantic.repositories.fixed-system-agent.mode", () -> "REMOTE");
        registry.add("semantic.repositories.fixed-system-agent.url", () -> remote().remoteUri());
        registry.add("semantic.repositories.fixed-system-agent.default-branch", () -> remote().branch());
        registry.add("semantic.repositories.semantic-vertical.mode", () -> "LOCAL_FIXTURE");
        registry.add("semantic.repositories.semantic-vertical.path", () -> FIXTURE.toString());
        registry.add("semantic.analysis.incoming.depth-two-node-budget", () -> "0");
    }

    @Test
    void should_prove_revision_pinned_http_discovery_directional_fragments_and_process_shutdown() throws Exception {
        scheduledAnnotationBeanPostProcessor.postProcessBeforeDestruction(
                workspaceIdleReaper, "jdtWorkspaceIdleReaper");
        LIFECYCLE_TICKER.reset();
        ensureAndCheckoutFixedRepository();

        ObjectNode fixedApi = discoverTarget(FIXED_REPOSITORY, "API", "AnalysisController", "analyzeOutgoing");
        ObjectNode fixedSchedule = discoverTarget(FIXED_REPOSITORY, "SCHEDULE", "JdtWorkspaceIdleReaper", "runOnce");
        assertThat(fixedSchedule.path("className").asText()).isEqualTo("JdtWorkspaceIdleReaper");

        JsonNode fixedFragment = analyze(FIXED_REPOSITORY, remote().revision(), fixedApi, 2, "outgoing");
        assertThat(fixedFragment.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(fixedFragment.path("analyzedRevision").asText()).isEqualTo(remote().revision());
        assertThat(fixedFragment.path("traversal").path("rootDirectCallsComplete").asBoolean()).isTrue();
        JsonNode fixedRoot = nodeById(fixedFragment, fixedFragment.path("rootNodeId").asText());
        assertFullSource(fixedRoot);
        assertProvenEdgesHaveCallRangesAndEvidence(fixedFragment);
        assertUnresolvedDescendantsAreExplicit(fixedFragment);

        Set<Long> fixedProcessIds = workspaceManager.activeProcessIds();
        assertThat(fixedProcessIds).hasSize(1);
        long fixedProcessId = fixedProcessIds.iterator().next();
        ProcessHandle fixedProcess = ProcessHandle.of(fixedProcessId)
                .orElseThrow(() -> new AssertionError("missing fixed JDT LS process"));
        assertThat(fixedProcess.isAlive()).isTrue();

        ensureRepository(FIXTURE_REPOSITORY);
        ObjectNode fixtureRest = discoverTarget(FIXTURE_REPOSITORY, "API", "OrderController", "place");
        ObjectNode fixtureMq = discoverTarget(FIXTURE_REPOSITORY, "MQ", "OrderListener", "consume");
        ObjectNode fixtureSchedule = discoverTarget(FIXTURE_REPOSITORY, "SCHEDULE", "OrderJob", "cleanup");
        assertThat(fixtureRest.path("parameterTypes")).containsExactly(objectMapper.getNodeFactory()
                .textNode("com.example.vertical.PlaceOrderRequest"));
        assertThat(fixtureSchedule.path("className").asText()).isEqualTo("OrderJob");

        JsonNode restFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", fixtureRest, 2, "outgoing");
        assertThat(restFragment.path("analyzedRevision").asText()).isEqualTo("FIXTURE");
        assertFullSource(nodeById(restFragment, restFragment.path("rootNodeId").asText()));
        assertDirectNodesHaveFullSource(restFragment);
        assertProvenEdgesHaveCallRangesAndEvidence(restFragment);

        ObjectNode depthBoundary = targetOfFirstDepthBoundary(restFragment);
        JsonNode reRootedFixtureFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", depthBoundary, 2, "outgoing");
        JsonNode reRootedFixtureRoot = nodeById(
                reRootedFixtureFragment, reRootedFixtureFragment.path("rootNodeId").asText());
        assertThat(reRootedFixtureFragment.path("analyzedRevision").asText()).isEqualTo("FIXTURE");
        assertThat(reRootedFixtureRoot.path("target")).isEqualTo(depthBoundary);
        assertFullSource(reRootedFixtureRoot);

        JsonNode mqFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", fixtureMq, 2, "outgoing");
        assertThat(mqFragment.path("analyzedRevision").asText()).isEqualTo("FIXTURE");
        JsonNode mqRoot = nodeById(mqFragment, mqFragment.path("rootNodeId").asText());
        JsonNode serviceNode = nodeByClassAndMethod(mqFragment, "OrderService", "placeFromMessage");
        assertFullSource(mqRoot);
        assertFullSource(serviceNode);
        assertThat(mqFragment.path("edges")).anySatisfy(edge -> {
            assertThat(edge.path("callerNodeId").asText()).isEqualTo(mqRoot.path("nodeId").asText());
            assertThat(edge.path("calleeNodeId").asText()).isEqualTo(serviceNode.path("nodeId").asText());
            assertThat(edge.path("evidence")).isNotEmpty();
        });

        ObjectNode ambiguityRoot = targetByClassAndMethod(mqFragment, "OrderService", "placeFromMessage");
        JsonNode ambiguityFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", ambiguityRoot, 2, "outgoing");
        JsonNode immutableAmbiguityFragment = ambiguityFragment.deepCopy();
        assertThat(ambiguityFragment.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(candidateIdentities(ambiguityFragment)).containsExactlyInAnyOrder(
                new TargetIdentity("CardPayment", "pay"),
                new TargetIdentity("CashPayment", "pay"),
                new TargetIdentity("CardPayment", "label"),
                new TargetIdentity("PaymentPort", "label"));
        assertThat(ambiguityFragment.path("edges")).noneSatisfy(edge -> {
            String calleeNodeId = edge.path("calleeNodeId").asText();
            JsonNode callee = nodeById(ambiguityFragment, calleeNodeId);
            assertThat(callee.path("target").path("className").asText())
                    .isNotIn("CardPayment", "CashPayment", "PaymentPort");
        });

        ObjectNode candidate = firstCandidate(ambiguityFragment);
        JsonNode candidateFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", candidate, 2, "outgoing");
        JsonNode candidateRoot = nodeById(candidateFragment, candidateFragment.path("rootNodeId").asText());
        assertThat(candidateRoot.path("target")).isEqualTo(candidate);
        assertFullSource(candidateRoot);
        assertThat(ambiguityFragment).isEqualTo(immutableAmbiguityFragment);

        ObjectNode serviceTarget = targetByClassAndMethod(mqFragment, "OrderService", "placeFromMessage");
        JsonNode incomingServiceFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", serviceTarget, 2, "incoming");
        JsonNode incomingServiceRoot = nodeById(incomingServiceFragment, incomingServiceFragment.path("rootNodeId").asText());
        assertFullSource(incomingServiceRoot);
        assertIncomingDirectNodesHaveFullSource(incomingServiceFragment);
        assertProvenEdgesHaveCallRangesAndEvidence(incomingServiceFragment);
        assertThat(incomingCallerIdentities(incomingServiceFragment))
                .contains(new TargetIdentity("OrderListener", "consume"));

        ObjectNode uncalledTarget = target("OverloadedTarget", "uncalled", List.of());
        JsonNode uncalledIncomingFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", uncalledTarget, 2, "incoming");
        assertThat(uncalledIncomingFragment.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(stream(uncalledIncomingFragment.path("nodes")).toList()).hasSize(1);
        assertFullSource(nodeById(uncalledIncomingFragment, uncalledIncomingFragment.path("rootNodeId").asText()));
        assertThat(stream(uncalledIncomingFragment.path("edges")).toList()).hasSize(0);
        assertThat(stream(uncalledIncomingFragment.path("warnings")).toList()).hasSize(0);
        assertThat(stream(uncalledIncomingFragment.path("errors")).toList()).hasSize(0);

        ObjectNode stringOverloadTarget = target("OverloadedTarget", "accept", List.of("java.lang.String"));
        JsonNode stringIncomingFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", stringOverloadTarget, 2, "incoming");
        assertThat(incomingCallerIdentities(stringIncomingFragment))
                .contains(new TargetIdentity("OverloadedCaller", "callString"))
                .doesNotContain(new TargetIdentity("OverloadedCaller", "callInt"));
        assertIncomingDirectNodesHaveFullSource(stringIncomingFragment);

        JsonNode budgetFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", stringOverloadTarget, 2, "incoming");
        JsonNode directCaller = nodeByClassAndMethod(budgetFragment, "OverloadedCaller", "callString");
        JsonNode cutoffCaller = nodeByClassAndMethod(budgetFragment, "OverloadedCaller", "callStringEntry");
        assertFullSource(directCaller);
        assertThat(cutoffCaller.path("contentState").asText()).isEqualTo("TARGET_ONLY");
        assertThat(cutoffCaller.path("traversalState").asText()).isEqualTo("BUDGET_CUTOFF");
        assertThat(budgetFragment.path("traversal").path("limitReason").asText()).isEqualTo("NODE_BUDGET");
        assertThat(stream(budgetFragment.path("warnings"))
                .map(warning -> warning.path("code").asText()).toList()).contains("NODE_BUDGET_REACHED");

        ObjectNode cutoffTarget = objectNode(cutoffCaller.path("target")).deepCopy();
        JsonNode rerootedCutoffFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", cutoffTarget, 2, "incoming");
        JsonNode rerootedCutoffRoot = nodeById(rerootedCutoffFragment, rerootedCutoffFragment.path("rootNodeId").asText());
        assertThat(rerootedCutoffRoot.path("target")).isEqualTo(cutoffTarget);
        assertFullSource(rerootedCutoffRoot);
        assertStatelessExpansion(rerootedCutoffFragment);

        ObjectNode callStringTarget = target("OverloadedCaller", "callString", List.of());
        JsonNode callStringOutgoingFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", callStringTarget, 2, "outgoing");
        assertThat(normalizedRootEdges(callStringOutgoingFragment, "outgoing"))
                .containsExactlyElementsOf(normalizedRootEdges(stringIncomingFragment, "incoming"));

        Set<Long> initialProcessIds = workspaceManager.activeProcessIds();
        assertThat(initialProcessIds).hasSize(2).contains(fixedProcessId);
        Set<Long> fixtureProcessIds = initialProcessIds.stream()
                .filter(processId -> processId.longValue() != fixedProcessId)
                .collect(Collectors.toSet());
        assertThat(fixtureProcessIds).hasSize(1);
        long fixtureProcessId = fixtureProcessIds.iterator().next();
        ProcessHandle fixtureProcess = ProcessHandle.of(fixtureProcessId)
                .orElseThrow(() -> new AssertionError("missing fixture JDT LS process"));
        assertThat(fixtureProcess.isAlive()).isTrue();

        RepositoryId fixtureRepositoryId = RepositoryId.of(FIXTURE_REPOSITORY);
        WorkspaceActivityLease indexingLease = workspaceManager.acquireActivity(
                fixtureRepositoryId, RepositoryRevision.fixture(), WorkspaceActivityKind.INDEXING);
        try {
            LIFECYCLE_TICKER.advance(Duration.ofMinutes(31));
            workspaceIdleReaper.runOnce();

            Set<Long> protectedProcessIds = workspaceManager.activeProcessIds();
            assertThat(protectedProcessIds).containsExactly(fixtureProcessId);
            assertThat(fixtureProcess.isAlive()).isTrue();
        } finally {
            indexingLease.close();
        }

        workspaceIdleReaper.runOnce();

        assertThat(fixtureProcess.isAlive()).isTrue();
        LIFECYCLE_TICKER.advance(Duration.ofMinutes(30));
        workspaceIdleReaper.runOnce();

        assertThat(fixtureProcess.isAlive()).isFalse();
        assertThat(workspaceManager.activeProcessIds()).isEmpty();

        JsonNode restartedFixtureFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", callStringTarget, 2, "outgoing");
        assertThat(restartedFixtureFragment.path("status").asText()).isEqualTo("SUCCESS");
        assertFullSource(nodeById(restartedFixtureFragment, restartedFixtureFragment.path("rootNodeId").asText()));
        Set<Long> restartedProcessIds = workspaceManager.activeProcessIds();
        assertThat(restartedProcessIds).hasSize(1);
        long restartedProcessId = restartedProcessIds.iterator().next();
        ProcessHandle restartedProcess = ProcessHandle.of(restartedProcessId)
                .orElseThrow(() -> new AssertionError("missing restarted JDT LS process"));
        assertThat(restartedProcess.isAlive()).isTrue();
        assertThat(restartedProcessId).isNotEqualTo(fixtureProcessId);

        workspaceManager.shutdownAll();

        Set<ProcessHandle> allCapturedProcesses = new HashSet<>();
        allCapturedProcesses.add(fixedProcess);
        allCapturedProcesses.add(fixtureProcess);
        allCapturedProcesses.add(restartedProcess);
        assertThat(allCapturedProcesses).allSatisfy(process -> assertThat(process.isAlive()).isFalse());
    }

    @AfterEach
    void shutdownAnyProcessLeftByAnInterruptedAssertion() {
        workspaceManager.shutdownAll();
    }

    @AfterAll
    static void closeRemote() throws IOException {
        if (Objects.nonNull(remote)) {
            remote.close();
        }
    }

    private void ensureAndCheckoutFixedRepository() throws Exception {
        ensureRepository(FIXED_REPOSITORY);
        mockMvc.perform(post("/v1/repositories/{repoId}/checkout", FIXED_REPOSITORY)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content("""
                                {"revision":"%s"}
                                """.formatted(remote().revision())))
                .andExpect(status().isOk());
    }

    private void ensureRepository(String repositoryId) throws Exception {
        mockMvc.perform(post("/v1/repositories/{repoId}/ensure", repositoryId)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
    }

    private ObjectNode discoverTarget(String repositoryId, String type, String className, String methodName)
            throws Exception {
        JsonNode entryPoints = response(mockMvc.perform(get("/v1/repositories/{repoId}/entry-points", repositoryId)
                        .queryParam("expectedRevision", remote().revision())
                        .queryParam("types", type)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode method = stream(entryPoints.path("entryPoints"))
                .flatMap(entryPoint -> stream(entryPoint.path("methods")))
                .filter(candidate -> type.equals(candidate.path("type").asText()))
                .filter(candidate -> className.equals(candidate.path("analysisTarget").path("target")
                        .path("className").asText()))
                .filter(candidate -> methodName.equals(candidate.path("analysisTarget").path("target")
                        .path("methodName").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing discovered target " + className + "#" + methodName));
        assertThat(method.path("analysisTarget").path("status").asText()).isEqualTo("RESOLVED");
        return objectNode(method.path("analysisTarget").path("target")).deepCopy();
    }

    private JsonNode analyze(String repositoryId, String revision, ObjectNode target, int depth, String direction)
            throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", revision);
        request.put("depth", depth);
        request.set("target", target.deepCopy());
        String requestedDirection = Objects.requireNonNull(direction, "graph direction is required");
        String endpoint = switch (requestedDirection) {
            case "outgoing", "incoming" -> "/v1/analyses/call-graphs/" + requestedDirection;
            default -> throw new IllegalArgumentException("unsupported graph direction: " + requestedDirection);
        };
        return response(mockMvc.perform(post(endpoint)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode response(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode nodeById(JsonNode fragment, String nodeId) {
        return stream(fragment.path("nodes"))
                .filter(node -> nodeId.equals(node.path("nodeId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node " + nodeId));
    }

    private JsonNode nodeByClassAndMethod(JsonNode fragment, String className, String methodName) {
        return stream(fragment.path("nodes"))
                .filter(node -> className.equals(node.path("target").path("className").asText()))
                .filter(node -> methodName.equals(node.path("target").path("methodName").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph target " + className + "#" + methodName));
    }

    private ObjectNode targetByClassAndMethod(JsonNode fragment, String className, String methodName) {
        return objectNode(nodeByClassAndMethod(fragment, className, methodName).path("target")).deepCopy();
    }

    private ObjectNode target(String className, String methodName, List<String> parameterTypes) {
        ObjectNode target = objectMapper.createObjectNode();
        target.put("sourceFile", "src/main/java/com/example/vertical/" + className + ".java");
        target.put("packageName", "com.example.vertical");
        target.put("className", className);
        target.put("methodName", methodName);
        target.set("parameterTypes", objectMapper.valueToTree(parameterTypes));
        return target;
    }

    private ObjectNode targetOfFirstDepthBoundary(JsonNode fragment) {
        JsonNode node = stream(fragment.path("nodes"))
                .filter(candidate -> "DEPTH_BOUNDARY".equals(candidate.path("traversalState").asText()))
                .filter(candidate -> candidate.path("target").isObject())
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixed graph has no invocable depth boundary"));
        return objectNode(node.path("target")).deepCopy();
    }

    private ObjectNode firstCandidate(JsonNode fragment) {
        JsonNode candidate = stream(fragment.path("warnings"))
                .flatMap(warning -> stream(warning.path("candidates")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ambiguous fragment has no candidate"));
        return objectNode(candidate).deepCopy();
    }

    private Set<TargetIdentity> candidateIdentities(JsonNode fragment) {
        return stream(fragment.path("warnings"))
                .flatMap(warning -> stream(warning.path("candidates")))
                .map(candidate -> new TargetIdentity(
                        candidate.path("className").asText(), candidate.path("methodName").asText()))
                .collect(Collectors.toSet());
    }

    private void assertFullSource(JsonNode node) {
        assertThat(node.path("contentState").asText()).isEqualTo("FULL_SOURCE");
        assertThat(node.path("declarationRange").path("sourceFile").asText()).isNotBlank();
        assertThat(node.path("declarationRange").path("start").path("line").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(node.path("declarationRange").path("end").path("line").asInt()).isGreaterThanOrEqualTo(0);
    }

    private void assertDirectNodesHaveFullSource(JsonNode fragment) {
        String rootNodeId = fragment.path("rootNodeId").asText();
        List<JsonNode> directNodes = stream(fragment.path("edges"))
                .filter(edge -> rootNodeId.equals(edge.path("callerNodeId").asText()))
                .map(edge -> nodeById(fragment, edge.path("calleeNodeId").asText()))
                .toList();
        assertThat(directNodes)
                .as("fixed response: %s", fragment)
                .isNotEmpty()
                .allSatisfy(this::assertFullSource);
    }

    private void assertIncomingDirectNodesHaveFullSource(JsonNode fragment) {
        String rootNodeId = fragment.path("rootNodeId").asText();
        List<JsonNode> directNodes = stream(fragment.path("edges"))
                .filter(edge -> rootNodeId.equals(edge.path("calleeNodeId").asText()))
                .map(edge -> nodeById(fragment, edge.path("callerNodeId").asText()))
                .toList();
        assertThat(directNodes)
                .as("incoming response: %s", fragment)
                .isNotEmpty()
                .allSatisfy(this::assertFullSource);
    }

    private Set<TargetIdentity> incomingCallerIdentities(JsonNode fragment) {
        String rootNodeId = fragment.path("rootNodeId").asText();
        return stream(fragment.path("edges"))
                .filter(edge -> rootNodeId.equals(edge.path("calleeNodeId").asText()))
                .map(edge -> nodeById(fragment, edge.path("callerNodeId").asText()).path("target"))
                .map(target -> new TargetIdentity(target.path("className").asText(), target.path("methodName").asText()))
                .collect(Collectors.toSet());
    }

    private List<ObjectNode> normalizedRootEdges(JsonNode fragment, String direction) {
        String rootNodeId = fragment.path("rootNodeId").asText();
        return stream(fragment.path("edges"))
                .filter(edge -> rootNodeId.equals(edge.path(rootEdgeField(direction)).asText()))
                .map(edge -> normalizeEdge(fragment, edge))
                .toList();
    }

    private String rootEdgeField(String direction) {
        String requestedDirection = Objects.requireNonNull(direction, "graph direction is required");
        return switch (requestedDirection) {
            case "outgoing" -> "callerNodeId";
            case "incoming" -> "calleeNodeId";
            default -> throw new IllegalArgumentException("unsupported graph direction: " + requestedDirection);
        };
    }

    private ObjectNode normalizeEdge(JsonNode fragment, JsonNode edge) {
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.set("caller", nodeById(fragment, edge.path("callerNodeId").asText()).path("target").deepCopy());
        normalized.set("callee", nodeById(fragment, edge.path("calleeNodeId").asText()).path("target").deepCopy());
        normalized.set("callSite", edge.path("callSite").deepCopy());
        return normalized;
    }

    private void assertStatelessExpansion(JsonNode fragment) {
        assertThat(fragment.has("cursor")).isFalse();
        assertThat(fragment.has("page")).isFalse();
        assertThat(fragment.has("token")).isFalse();
    }

    private void assertProvenEdgesHaveCallRangesAndEvidence(JsonNode fragment) {
        assertThat(stream(fragment.path("edges")).toList()).isNotEmpty().allSatisfy(edge -> {
            assertThat(edge.path("callSite").path("sourceFile").asText()).isNotBlank();
            assertThat(edge.path("callSite").path("start").path("line").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(edge.path("callSite").path("end").path("line").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(edge.path("resolutionStrategy").asText()).isNotBlank();
            assertThat(edge.path("evidence")).isNotEmpty();
        });
    }

    private void assertUnresolvedDescendantsAreExplicit(JsonNode fragment) {
        assertThat(stream(fragment.path("warnings")).toList()).isNotEmpty().allSatisfy(warning -> {
            assertThat(warning.path("code").asText()).isEqualTo("DESCENDANT_CALL_UNRESOLVED");
            assertThat(warning.path("message").asText()).isEqualTo("descendant call target is not proven");
            assertThat(warning.path("callExpression").asText()).isNotBlank();
            assertThat(warning.path("callSite").path("sourceFile").asText()).isNotBlank();
            assertThat(warning.path("callSite").path("start").path("line").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(warning.path("callSite").path("end").path("line").asInt()).isGreaterThanOrEqualTo(0);
        });
    }

    private ObjectNode objectNode(JsonNode node) {
        assertThat(node.isObject()).isTrue();
        return (ObjectNode) node;
    }

    private static Stream<JsonNode> stream(JsonNode nodes) {
        return nodes.valueStream();
    }

    private static Path requireJdtlsHome() {
        String configuredHome = System.getenv("JDTLS_HOME");
        if (!StringUtils.hasText(configuredHome)) {
            throw new IllegalStateException("JDTLS_HOME must be configured for SemanticFacadeVerticalJdtLsIT");
        }
        Path home = Path.of(configuredHome);
        if (!Files.isDirectory(home)) {
            throw new IllegalStateException("JDTLS_HOME must point at a directory: " + home);
        }
        return home;
    }

    private static synchronized CurrentProjectRemoteFixture remote() {
        if (Objects.isNull(remote)) {
            try {
                remote = CurrentProjectRemoteFixture.create();
            } catch (IOException | GitAPIException | URISyntaxException exception) {
                throw new IllegalStateException("could not create current-project remote fixture", exception);
            }
        }
        return remote;
    }

    private record TargetIdentity(String className, String methodName) {
    }

    @TestConfiguration
    static class LifecycleTestConfiguration {

        @Bean
        @Primary
        JdtMonotonicTicker lifecycleTicker() {
            return LIFECYCLE_TICKER;
        }
    }

    private static final class MutableTicker implements JdtMonotonicTicker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long readNanos() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        void reset() {
            nanos.set(0L);
        }
    }

}
