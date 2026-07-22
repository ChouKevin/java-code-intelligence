package com.java.semantic.semantic.adapter.jdtls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.semantic.api.security.ApiTokenFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real-process, authenticated HTTP proof for the revision-pinned outgoing semantic facade. */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("jdtls-it")
class SemanticFacadeVerticalJdtLsIT {

    private static final String TOKEN = "semantic-facade-vertical-token";
    private static final String FIXED_REPOSITORY = "fixed-system-agent";
    private static final String FIXTURE_REPOSITORY = "semantic-vertical";
    private static final String FIXED_REVISION = "406dddba50e830a95a93a3d204b0f42c5d459a09";
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/semantic-facade-vertical")
            .toAbsolutePath().normalize();
    private static final Path WORKSPACE_DATA = Path.of("target/semantic-facade-vertical-jdtls")
            .toAbsolutePath().normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DefaultJdtWorkspaceManager workspaceManager;

    @DynamicPropertySource
    static void semanticFacadeProperties(DynamicPropertyRegistry registry) {
        registry.add("semantic.api.api-token", () -> TOKEN);
        registry.add("semantic.data-root", () -> WORKSPACE_DATA.resolve("repositories").toString());
        registry.add("semantic.jdtls.home", () -> requireJdtlsHome().toString());
        registry.add("semantic.jdtls.workspace-data-root", () -> WORKSPACE_DATA.resolve("workspaces").toString());
        registry.add("semantic.jdtls.max-active-workspaces", () -> "2");
        registry.add("semantic.repositories.fixed-system-agent.mode", () -> "REMOTE");
        registry.add("semantic.repositories.fixed-system-agent.url", () -> enclosingRepository().toUri().toString());
        registry.add("semantic.repositories.fixed-system-agent.default-branch", () -> "tmp/plan-c-task1");
        registry.add("semantic.repositories.semantic-vertical.mode", () -> "LOCAL_FIXTURE");
        registry.add("semantic.repositories.semantic-vertical.path", () -> FIXTURE.toString());
    }

    @Test
    void should_prove_revision_pinned_http_discovery_outgoing_fragments_and_process_shutdown() throws Exception {
        ensureAndCheckoutFixedRepository();

        ObjectNode fixedApi = discoverTarget(FIXED_REPOSITORY, "API", "AnalysisController", "getApiCallGraph");
        ObjectNode fixedSchedule = discoverTarget(FIXED_REPOSITORY, "SCHEDULE", "RateLimitingService", "cleanup");
        assertThat(fixedSchedule.path("className").asText()).isEqualTo("RateLimitingService");

        JsonNode fixedFragment = analyze(FIXED_REPOSITORY, FIXED_REVISION, fixedApi, 2);
        assertThat(fixedFragment.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(fixedFragment.path("analyzedRevision").asText()).isEqualTo(FIXED_REVISION);
        assertThat(fixedFragment.path("traversal").path("rootDirectCallsComplete").asBoolean()).isTrue();
        JsonNode fixedRoot = nodeById(fixedFragment, fixedFragment.path("rootNodeId").asText());
        assertFullSource(fixedRoot);
        assertProvenEdgesHaveCallRangesAndEvidence(fixedFragment);
        assertUnresolvedDescendantsAreExplicit(fixedFragment);

        ensureRepository(FIXTURE_REPOSITORY);
        ObjectNode fixtureRest = discoverTarget(FIXTURE_REPOSITORY, "API", "OrderController", "place");
        ObjectNode fixtureMq = discoverTarget(FIXTURE_REPOSITORY, "MQ", "OrderListener", "consume");
        ObjectNode fixtureSchedule = discoverTarget(FIXTURE_REPOSITORY, "SCHEDULE", "OrderJob", "cleanup");
        assertThat(fixtureRest.path("parameterTypes")).containsExactly(objectMapper.getNodeFactory()
                .textNode("com.example.vertical.PlaceOrderRequest"));
        assertThat(fixtureSchedule.path("className").asText()).isEqualTo("OrderJob");

        JsonNode restFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", fixtureRest, 2);
        assertThat(restFragment.path("analyzedRevision").asText()).isEqualTo("FIXTURE");
        assertFullSource(nodeById(restFragment, restFragment.path("rootNodeId").asText()));
        assertDirectNodesHaveFullSource(restFragment);
        assertProvenEdgesHaveCallRangesAndEvidence(restFragment);

        ObjectNode depthBoundary = targetOfFirstDepthBoundary(restFragment);
        JsonNode reRootedFixtureFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", depthBoundary, 2);
        JsonNode reRootedFixtureRoot = nodeById(
                reRootedFixtureFragment, reRootedFixtureFragment.path("rootNodeId").asText());
        assertThat(reRootedFixtureFragment.path("analyzedRevision").asText()).isEqualTo("FIXTURE");
        assertThat(reRootedFixtureRoot.path("target")).isEqualTo(depthBoundary);
        assertFullSource(reRootedFixtureRoot);

        JsonNode mqFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", fixtureMq, 2);
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
        JsonNode ambiguityFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", ambiguityRoot, 2);
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
        JsonNode candidateFragment = analyze(FIXTURE_REPOSITORY, "FIXTURE", candidate, 2);
        JsonNode candidateRoot = nodeById(candidateFragment, candidateFragment.path("rootNodeId").asText());
        assertThat(candidateRoot.path("target")).isEqualTo(candidate);
        assertFullSource(candidateRoot);
        assertThat(ambiguityFragment).isEqualTo(immutableAmbiguityFragment);

        Set<Long> processIds = workspaceManager.activeProcessIds();
        assertThat(processIds).isNotEmpty();
        List<ProcessHandle> processes = processIds.stream()
                .map(ProcessHandle::of)
                .flatMap(Optional::stream)
                .toList();
        assertThat(processes).hasSize(processIds.size()).allSatisfy(process ->
                assertThat(process.isAlive()).isTrue());

        workspaceManager.shutdownAll();

        assertThat(processes).allSatisfy(process -> assertThat(process.isAlive()).isFalse());
    }

    @AfterEach
    void shutdownAnyProcessLeftByAnInterruptedAssertion() {
        workspaceManager.shutdownAll();
    }

    private void ensureAndCheckoutFixedRepository() throws Exception {
        ensureRepository(FIXED_REPOSITORY);
        mockMvc.perform(post("/v1/repositories/{repoId}/checkout", FIXED_REPOSITORY)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content("""
                                {"revision":"406dddba50e830a95a93a3d204b0f42c5d459a09"}
                                """))
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

    private JsonNode analyze(String repositoryId, String revision, ObjectNode target, int depth) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", revision);
        request.put("depth", depth);
        request.set("target", target.deepCopy());
        return response(mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
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
        assertThat(node.path("methodBody").asText()).isNotBlank();
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
        Iterable<JsonNode> iterable = () -> nodes.elements();
        return StreamSupport.stream(iterable.spliterator(), false);
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

    private static Path enclosingRepository() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (!Files.isDirectory(current.resolve(".git"))) {
            Path parent = current.getParent();
            if (Objects.isNull(parent)) {
                throw new IllegalStateException("could not locate enclosing Git repository from " + current);
            }
            current = parent;
        }
        return current;
    }

    private record TargetIdentity(String className, String methodName) {
    }

}
