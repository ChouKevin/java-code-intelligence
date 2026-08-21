package com.java.semantic.semantic.adapter.jdtls;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.java.semantic.api.dto.RepositoryStatusResponse;
import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.security.ApiTokenFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stable UAT fixtures exercised through the public semantic HTTP contract and a real JDT LS. */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("jdtls-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StableUatFixtureJdtLsIT {

    private static final String TOKEN = "stable-uat-fixture-token";
    private static final String FIXTURE_REVISION = "FIXTURE";
    private static final String PAYMENT_REPOSITORY = "payment-service";
    private static final String ORDER_REPOSITORY = "order-service";
    private static final String PAYMENT_DISPLAY_NAME = "Payment Service";
    private static final String ORDER_DISPLAY_NAME = "Order Service";
    private static final Path PAYMENT_FIXTURE = Path.of("fixtures/uat/payment-service").toAbsolutePath().normalize();
    private static final Path ORDER_FIXTURE = Path.of("fixtures/uat/order-service").toAbsolutePath().normalize();
    private static final Path WORKSPACE_DATA = Path.of("target/stable-uat-fixture-jdtls").toAbsolutePath().normalize();
    private static final SourceTypeIdentityPayload PAYMENT_METHOD = sourceType(
            "com.example.payment", "PaymentMethod", "src/main/java/com/example/payment/PaymentMethod.java");
    private static final SourceTypeIdentityPayload PAYMENT_FEE_CALCULATOR = sourceType(
            "com.example.payment", "PaymentFeeCalculator", "src/main/java/com/example/payment/PaymentFeeCalculator.java");
    private static final SourceTypeIdentityPayload PAYMENT_QUERY_CONTROLLER = sourceType(
            "com.example.payment", "PaymentQueryController", "src/main/java/com/example/payment/PaymentQueryController.java");
    private static final SourceTypeIdentityPayload ORDER_SERVICE = sourceType(
            "com.example.order", "OrderService", "src/main/java/com/example/order/OrderService.java");
    private static final SourceTypeIdentityPayload ORDER_QUERY_CONTROLLER = sourceType(
            "com.example.order", "OrderQueryController", "src/main/java/com/example/order/OrderQueryController.java");
    private static final MethodTargetPayload FEE_FORMULA_METHOD = methodTarget(
            "com.example.payment", "PaymentFeeSettings", "src/main/java/com/example/payment/PaymentFeeSettings.java",
            "loadFeeFormulaJson", List.of("com.example.payment.PaymentMethod"));
    private static final MethodTargetPayload PAYMENT_METHODS_ENTRY_POINT = methodTarget(
            "com.example.payment", "PaymentQueryController", "src/main/java/com/example/payment/PaymentQueryController.java",
            "paymentMethods", List.of());
    private static final MethodTargetPayload ORDER_CANCEL_METHOD = methodTarget(
            "com.example.order", "OrderService", "src/main/java/com/example/order/OrderService.java",
            "cancel", List.of("java.lang.String"));
    private static final MethodTargetPayload ORDER_FIND_METHOD = methodTarget(
            "com.example.order", "OrderService", "src/main/java/com/example/order/OrderService.java",
            "findOrder", List.of("java.lang.String"));
    private static final MethodTargetPayload ORDER_CANCEL_ENTRY_POINT = methodTarget(
            "com.example.order", "OrderQueryController", "src/main/java/com/example/order/OrderQueryController.java",
            "cancelOrder", List.of("java.lang.String"));

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

    @BeforeAll
    static void cleanWorkspaceDataBeforeStarting() throws IOException {
        deleteWorkspaceData();
        deleteGeneratedProjectMetadata(PAYMENT_FIXTURE);
        deleteGeneratedProjectMetadata(ORDER_FIXTURE);
    }

    @AfterAll
    void cleanWorkspaceDataAfterRunning() throws IOException {
        workspaceManager.shutdownAll();
        deleteWorkspaceData();
        deleteGeneratedProjectMetadata(PAYMENT_FIXTURE);
        deleteGeneratedProjectMetadata(ORDER_FIXTURE);
    }

    @DynamicPropertySource
    static void stableFixtureProperties(DynamicPropertyRegistry registry) {
        registry.add("semantic.api.api-token", () -> TOKEN);
        registry.add("semantic.data-root", () -> WORKSPACE_DATA.resolve("repositories").toString());
        registry.add("semantic.jdtls.home", () -> requireJdtlsHome().toString());
        registry.add("semantic.jdtls.workspace-data-root", () -> WORKSPACE_DATA.resolve("workspaces").toString());
        registry.add("semantic.jdtls.maintenance-interval", () -> "24h");
        registry.add("semantic.repositories.payment-service.mode", () -> "LOCAL_FIXTURE");
        registry.add("semantic.repositories.payment-service.display-name", () -> PAYMENT_DISPLAY_NAME);
        registry.add("semantic.repositories.payment-service.path", () -> PAYMENT_FIXTURE.toString());
        registry.add("semantic.repositories.order-service.mode", () -> "LOCAL_FIXTURE");
        registry.add("semantic.repositories.order-service.display-name", () -> ORDER_DISPLAY_NAME);
        registry.add("semantic.repositories.order-service.path", () -> ORDER_FIXTURE.toString());
    }

    @Test
    void should_publish_payment_fixture_concepts_enum_members_and_internal_references() throws Exception {
        disableWorkspaceScheduler();

        ensureRepository(PAYMENT_REPOSITORY, PAYMENT_DISPLAY_NAME);
        assertRepositoryStatus(PAYMENT_REPOSITORY, PAYMENT_DISPLAY_NAME);
        JsonNode paymentEntryPoints = entryPoints(PAYMENT_REPOSITORY);
        assertApiEntryPoint(
                paymentEntryPoints, PAYMENT_QUERY_CONTROLLER, PAYMENT_METHODS_ENTRY_POINT, "/payment-methods", "GET");

        JsonNode concepts = postJson("/v1/discovery/concepts", conceptRequest(PAYMENT_REPOSITORY, "Payment", "TYPE"));
        assertBoundRevision(concepts, PAYMENT_REPOSITORY);
        assertTypeConceptCandidate(concepts, PAYMENT_METHOD);
        assertTypeConceptCandidate(concepts, PAYMENT_FEE_CALCULATOR);

        JsonNode members = postJson("/v1/discovery/type-members", typeMembersRequest(PAYMENT_REPOSITORY, PAYMENT_METHOD));
        assertBoundRevision(members, PAYMENT_REPOSITORY);
        assertThat(members.path("members"))
                .filteredOn(member -> "ENUM_CONSTANT".equals(member.path("kind").asText()))
                .extracting(member -> member.path("identity").path("name").asText())
                .containsExactlyInAnyOrder("CREDIT_CARD", "BANK_TRANSFER", "WALLET");
        members.path("members").forEach(member -> {
            if ("ENUM_CONSTANT".equals(member.path("kind").asText())) {
                assertSourceRange(
                        member.path("identity").path("ownerType").path("sourceFile").asText(),
                        member.path("declarationRange"));
            }
        });

        JsonNode references = postJson("/v1/discovery/internal-references", internalReferencesRequest(PAYMENT_REPOSITORY));
        assertBoundRevision(references, PAYMENT_REPOSITORY);
        assertThat(references.path("targetDeclaration").path("target").path("identity").path("methodName").asText())
                .isEqualTo("loadFeeFormulaJson");
        assertSourceRange(
                references.path("targetDeclaration").path("target").path("identity")
                        .path("sourceType").path("sourceFile").asText(),
                references.path("targetDeclaration").path("declarationRange"));
        assertThat(references.path("referenceGroups"))
                .anySatisfy(group -> {
                    JsonNode method = group.path("context").path("method");
                    assertThat(method.path("methodName").asText()).isEqualTo("calculate");
                    assertThat(method.path("sourceType").path("sourceFile").asText())
                            .endsWith("PaymentFeeCalculator.java");
                    group.path("representativeReferences").forEach(reference ->
                            assertSourceRange(
                                    method.path("sourceType").path("sourceFile").asText(),
                                    reference.path("range")));
                });
    }

    @Test
    void should_publish_order_cancel_method_source_and_outgoing_graph() throws Exception {
        disableWorkspaceScheduler();

        ensureRepository(ORDER_REPOSITORY, ORDER_DISPLAY_NAME);
        assertRepositoryStatus(ORDER_REPOSITORY, ORDER_DISPLAY_NAME);
        JsonNode orderEntryPoints = entryPoints(ORDER_REPOSITORY);
        assertApiEntryPoint(
                orderEntryPoints, ORDER_QUERY_CONTROLLER, ORDER_CANCEL_ENTRY_POINT, "/orders/{orderId}/cancel", "POST");

        JsonNode concepts = postJson("/v1/discovery/concepts", conceptRequest(ORDER_REPOSITORY, "Order", "TYPE"));
        assertBoundRevision(concepts, ORDER_REPOSITORY);
        assertTypeConceptCandidate(concepts, ORDER_SERVICE);

        JsonNode methodSource = postJson("/v1/discovery/method-source", methodTargetRequest(ORDER_REPOSITORY, ORDER_CANCEL_METHOD));
        assertBoundRevision(methodSource, ORDER_REPOSITORY);
        assertThat(methodSource.path("segment").path("content").asText())
                .contains("findOrder(orderId).cancel()")
                .contains("orderRepository.save(cancelledOrder)")
                .doesNotContain("refund");
        assertSourceRange(
                methodSource.path("declarationLocation").path("sourceFile").asText(),
                methodSource.path("declarationLocation").path("range"));
        assertSourceRange(
                methodSource.path("segment").path("location").path("sourceFile").asText(),
                methodSource.path("segment").path("location").path("range"));

        JsonNode graph = postJson("/v1/analyses/call-graphs/outgoing", outgoingGraphRequest(ORDER_REPOSITORY));
        assertThat(graph.path("analyzedRevision").asText()).isEqualTo(FIXTURE_REVISION);
        JsonNode rootNode = graphNodeById(graph, graph.path("rootNodeId").asText());
        assertMethodTarget(rootNode.path("target"), ORDER_CANCEL_METHOD);
        assertGraphNodeSourceRange(rootNode);

        JsonNode findOrderNode = graph.path("nodes").valueStream()
                .filter(node -> matchesMethodTarget(node.path("target"), ORDER_FIND_METHOD))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node for OrderService.findOrder(String)"));
        assertGraphNodeSourceRange(findOrderNode);

        JsonNode rootToFindOrderEdge = graph.path("edges").valueStream()
                .filter(edge -> rootNode.path("nodeId").asText().equals(edge.path("callerNodeId").asText()))
                .filter(edge -> findOrderNode.path("nodeId").asText().equals(edge.path("calleeNodeId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing OrderService.cancel(String) to findOrder(String) graph edge"));
        assertSourceRange(
                rootToFindOrderEdge.path("callSite").path("sourceFile").asText(),
                rootToFindOrderEdge.path("callSite").path("range"));

        graph.path("nodes").forEach(node -> {
            JsonNode target = node.path("target");
            if (target.isObject() && node.path("declarationRange").isObject()) {
                assertGraphNodeSourceRange(node);
            }
        });
    }

    private void disableWorkspaceScheduler() {
        scheduledAnnotationBeanPostProcessor.postProcessBeforeDestruction(
                workspaceIdleReaper, "jdtWorkspaceIdleReaper");
    }

    private void ensureRepository(String repositoryId, String displayName) throws Exception {
        RepositoryStatusResponse response = repositoryStatus(mockMvc.perform(
                        post("/v1/repositories/{repoId}/ensure", repositoryId)
                                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn());
        assertRepository(response, repositoryId, displayName);
    }

    private void assertRepositoryStatus(String repositoryId, String displayName) throws Exception {
        RepositoryStatusResponse response = repositoryStatus(mockMvc.perform(
                        get("/v1/repositories/{repoId}", repositoryId)
                                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn());
        assertRepository(response, repositoryId, displayName);
    }

    private void assertRepository(RepositoryStatusResponse response, String repositoryId, String displayName) {
        assertThat(response.repoId()).isEqualTo(repositoryId);
        assertThat(response.displayName()).isEqualTo(displayName);
        assertThat(response.mode()).isEqualTo("LOCAL_FIXTURE");
        assertThat(response.currentRevision()).contains(FIXTURE_REVISION);
    }

    private JsonNode entryPoints(String repositoryId) throws Exception {
        JsonNode response = response(mockMvc.perform(get("/v1/repositories/{repoId}/entry-points", repositoryId)
                        .queryParam("expectedRevision", FIXTURE_REVISION)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn());
        assertBoundRevision(response, repositoryId);
        assertPublicSourceLocations(response);
        assertThat(response.path("entryPoints")).isNotEmpty();
        return response;
    }

    private JsonNode postJson(String endpoint, ObjectNode request) throws Exception {
        JsonNode response = response(mockMvc.perform(post(endpoint)
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        assertPublicSourceLocations(response);
        return response;
    }

    private ObjectNode conceptRequest(String repositoryId, String term, String kind) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", FIXTURE_REVISION);
        request.putArray("terms").addObject()
                .put("value", term)
                .put("matchMode", "TOKEN_PREFIX");
        request.putArray("kinds").add(kind);
        request.put("operator", "ALL");
        return request;
    }

    private ObjectNode typeMembersRequest(String repositoryId, SourceTypeIdentityPayload sourceType) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", FIXTURE_REVISION);
        request.set("sourceType", objectMapper.valueToTree(sourceType));
        request.putArray("memberKinds").add("ENUM_CONSTANT");
        request.put("offset", 0);
        request.put("limit", 50);
        return request;
    }

    private ObjectNode internalReferencesRequest(String repositoryId) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", FIXTURE_REVISION);
        ObjectNode target = request.putObject("target");
        target.put("kind", "METHOD");
        target.set("identity", objectMapper.valueToTree(FEE_FORMULA_METHOD));
        request.put("offset", 0);
        request.put("limit", 20);
        return request;
    }

    private ObjectNode methodTargetRequest(String repositoryId, MethodTargetPayload target) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", repositoryId);
        request.put("expectedRevision", FIXTURE_REVISION);
        request.set("target", objectMapper.valueToTree(target));
        return request;
    }

    private ObjectNode outgoingGraphRequest(String repositoryId) {
        ObjectNode request = methodTargetRequest(repositoryId, ORDER_CANCEL_METHOD);
        request.put("depth", 2);
        return request;
    }

    private JsonNode response(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private RepositoryStatusResponse repositoryStatus(MvcResult result) throws IOException {
        return objectMapper.readValue(result.getResponse().getContentAsString(), RepositoryStatusResponse.class);
    }

    private void assertBoundRevision(JsonNode response, String repositoryId) {
        assertThat(response.path("repoId").asText()).isEqualTo(repositoryId);
        assertThat(response.path("analyzedRevision").asText()).isEqualTo(FIXTURE_REVISION);
    }

    private static void assertApiEntryPoint(
            JsonNode response,
            SourceTypeIdentityPayload expectedSourceType,
            MethodTargetPayload expectedMethod,
            String expectedApiUrl,
            String expectedHttpMethod) {
        JsonNode entryPoint = response.path("entryPoints").valueStream()
                .filter(candidate -> matchesSourceType(candidate.path("sourceType"), expectedSourceType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing entry-point class " + expectedSourceType.javaType().className()));
        assertSourceType(entryPoint.path("sourceType"), expectedSourceType);

        JsonNode method = entryPoint.path("methods").valueStream()
                .filter(candidate -> expectedApiUrl.equals(candidate.path("apiUrl").asText()))
                .filter(candidate -> candidate.path("httpMethods").valueStream()
                        .map(JsonNode::asText)
                        .anyMatch(expectedHttpMethod::equals))
                .filter(candidate -> matchesMethodTarget(candidate.path("analysisTarget").path("target"), expectedMethod))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + expectedHttpMethod + " " + expectedApiUrl + " entry-point method "
                                + expectedMethod.methodName()));
        assertThat(method.path("analysisTarget").path("status").asText()).isEqualTo("RESOLVED");
        assertMethodTarget(method.path("analysisTarget").path("target"), expectedMethod);
    }

    private static void assertTypeConceptCandidate(JsonNode response, SourceTypeIdentityPayload expected) {
        JsonNode candidate = response.path("candidates").valueStream()
                .filter(value -> "TYPE".equals(value.path("identity").path("kind").asText()))
                .filter(value -> matchesSourceType(value.path("identity").path("sourceType"), expected))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing type concept " + expected.javaType().className()));
        assertSourceType(candidate.path("identity").path("sourceType"), expected);
    }

    private static JsonNode graphNodeById(JsonNode graph, String nodeId) {
        return graph.path("nodes").valueStream()
                .filter(node -> nodeId.equals(node.path("nodeId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing graph node " + nodeId));
    }

    private static boolean matchesSourceType(JsonNode actual, SourceTypeIdentityPayload expected) {
        return expected.javaType().packageName().equals(actual.path("javaType").path("packageName").asText())
                && expected.javaType().className().equals(actual.path("javaType").path("className").asText())
                && expected.sourceFile().equals(actual.path("sourceFile").asText());
    }

    private static void assertSourceType(JsonNode actual, SourceTypeIdentityPayload expected) {
        assertThat(actual.path("javaType").path("packageName").asText())
                .isEqualTo(expected.javaType().packageName());
        assertThat(actual.path("javaType").path("className").asText())
                .isEqualTo(expected.javaType().className());
        assertThat(actual.path("sourceFile").asText()).isEqualTo(expected.sourceFile());
    }

    private static boolean matchesMethodTarget(JsonNode target, MethodTargetPayload expected) {
        return expected.sourceType().javaType().packageName()
                        .equals(target.path("sourceType").path("javaType").path("packageName").asText())
                && expected.sourceType().javaType().className()
                        .equals(target.path("sourceType").path("javaType").path("className").asText())
                && expected.sourceType().sourceFile()
                        .equals(target.path("sourceType").path("sourceFile").asText())
                && expected.methodName().equals(target.path("methodName").asText())
                && target.path("parameterTypes").valueStream().map(JsonNode::asText).toList()
                        .equals(expected.parameterTypes());
    }

    private static void assertMethodTarget(JsonNode target, MethodTargetPayload expected) {
        assertThat(target.path("sourceType").path("javaType").path("packageName").asText())
                .isEqualTo(expected.sourceType().javaType().packageName());
        assertThat(target.path("sourceType").path("javaType").path("className").asText())
                .isEqualTo(expected.sourceType().javaType().className());
        assertThat(target.path("sourceType").path("sourceFile").asText())
                .isEqualTo(expected.sourceType().sourceFile());
        assertThat(target.path("methodName").asText()).isEqualTo(expected.methodName());
        assertThat(target.path("parameterTypes")).extracting(JsonNode::asText)
                .containsExactlyElementsOf(expected.parameterTypes());
    }

    private static void assertGraphNodeSourceRange(JsonNode node) {
        assertSourceRange(
                node.path("target").path("sourceType").path("sourceFile").asText(),
                node.path("declarationRange"));
    }

    private static void assertPublicSourceLocations(JsonNode response) {
        assertPublicSourceLocations(response, "");
    }

    private static void assertPublicSourceLocations(JsonNode node, String inheritedSourceFile) {
        if (node.isArray()) {
            node.forEach(child -> assertPublicSourceLocations(child, inheritedSourceFile));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String sourceFile = sourceFileFor(node, inheritedSourceFile);
        if (node.has("sourceFile")) {
            assertSourceFile(node.path("sourceFile").asText());
        }
        if (node.has("range")) {
            assertSourceRange(sourceFile, node.path("range"));
        }
        if (node.has("declarationRange")) {
            assertSourceRange(sourceFile, node.path("declarationRange"));
        }
        node.forEach(child -> assertPublicSourceLocations(child, sourceFile));
    }

    private static String sourceFileFor(JsonNode node, String inheritedSourceFile) {
        List<String> sourceFiles = List.of(
                node.path("sourceFile").asText(),
                node.path("sourceType").path("sourceFile").asText(),
                node.path("target").path("sourceType").path("sourceFile").asText(),
                node.path("target").path("identity").path("sourceType").path("sourceFile").asText(),
                node.path("identity").path("sourceType").path("sourceFile").asText(),
                node.path("identity").path("ownerType").path("sourceFile").asText(),
                node.path("method").path("sourceType").path("sourceFile").asText(),
                node.path("context").path("sourceType").path("sourceFile").asText(),
                node.path("context").path("method").path("sourceType").path("sourceFile").asText(),
                inheritedSourceFile);
        return sourceFiles.stream().filter(StringUtils::hasText).findFirst().orElse("");
    }

    private static void assertSourceRange(String sourceFile, JsonNode range) {
        assertSourceFile(sourceFile);

        int startLine = range.path("start").path("line").asInt(-1);
        int startCharacter = range.path("start").path("character").asInt(-1);
        int endLine = range.path("end").path("line").asInt(-1);
        int endCharacter = range.path("end").path("character").asInt(-1);
        assertThat(startLine).isGreaterThanOrEqualTo(0);
        assertThat(startCharacter).isGreaterThanOrEqualTo(0);
        assertThat(endLine).isGreaterThanOrEqualTo(startLine);
        assertThat(endCharacter).isGreaterThanOrEqualTo(0);
        if (endLine == startLine) {
            assertThat(endCharacter).isGreaterThanOrEqualTo(startCharacter);
        }
    }

    private static void assertSourceFile(String sourceFile) {
        assertThat(sourceFile).isNotBlank().doesNotContain("\\\\").doesNotMatch("^[A-Za-z]:.*");
        Path normalized = Path.of(sourceFile).normalize();
        String normalizedSourceFile = normalized.toString().replace('\\', '/');
        assertThat(normalized.isAbsolute()).isFalse();
        assertThat(normalizedSourceFile).isEqualTo(sourceFile);
        assertThat(normalizedSourceFile).isNotEqualTo(".");
        assertThat(normalized.getNameCount()).isGreaterThan(0);
        for (Path segment : normalized) {
            assertThat(segment.toString()).isNotBlank().isNotIn(".", "..");
        }
    }

    private static SourceTypeIdentityPayload sourceType(String packageName, String className, String sourceFile) {
        return new SourceTypeIdentityPayload(new JavaTypeIdentityPayload(packageName, className), sourceFile);
    }

    private static MethodTargetPayload methodTarget(
            String packageName,
            String className,
            String sourceFile,
            String methodName,
            List<String> parameterTypes) {
        return new MethodTargetPayload(sourceType(packageName, className, sourceFile), methodName, parameterTypes);
    }

    private static Path requireJdtlsHome() {
        String configuredHome = System.getenv("JDTLS_HOME");
        if (!StringUtils.hasText(configuredHome)) {
            throw new IllegalStateException("JDTLS_HOME must be configured for StableUatFixtureJdtLsIT");
        }
        Path home = Path.of(configuredHome);
        if (!Files.isDirectory(home)) {
            throw new IllegalStateException("JDTLS_HOME must point at a directory: " + home);
        }
        return home;
    }

    private static void deleteWorkspaceData() throws IOException {
        deleteDirectory(WORKSPACE_DATA, "could not clean JDT LS test workspace");
    }

    private static void deleteGeneratedProjectMetadata(Path fixturePath) throws IOException {
        Files.deleteIfExists(fixturePath.resolve(".classpath"));
        Files.deleteIfExists(fixturePath.resolve(".project"));
        deleteDirectory(fixturePath.resolve(".settings"), "could not clean generated JDT LS project metadata");
    }

    private static void deleteDirectory(Path directory, String failureMessage) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(failureMessage, exception);
                }
            });
        }
    }
}
