package com.java.semantic.api;

import com.java.semantic.identity.MethodTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.semantic.api.dto.GraphEdgeResponse;
import com.java.semantic.api.dto.GraphErrorResponse;
import com.java.semantic.api.dto.GraphNodeResponse;
import com.java.semantic.api.dto.GraphTraversalResponse;
import com.java.semantic.api.dto.GraphWarningResponse;
import com.java.semantic.api.dto.MethodTargetRequest;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.PositionResponse;
import com.java.semantic.api.dto.SourceRangeResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class OpenApiContractTest {

    private static final String REVISION_PATTERN = "^[0-9a-f]{40}$|^FIXTURE$";
    private static final String SOURCE_FILE_PATTERN = "^[^\\u0000-\\u001F\\u007F-\\u009F]*[^\\u0000-\\u0020\\u007F-\\u009F\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028-\\u2029\\u205F\\u3000][^\\u0000-\\u001F\\u007F-\\u009F]*$";
    private static final String CLASS_NAME_PATTERN = "^[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*(?:\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*)*$";
    private static final String METHOD_NAME_PATTERN = "^[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*$";

    private Map<String, Object> document;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/openapi/semantic-api-v1.yaml"),
                "OpenAPI document is required")) {
            document = map(new Yaml().load(input));
        }
    }

    @Test
    void should_expose_outgoing_and_incoming_graph_operations_with_expected_responses() {
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
                "/v1/repositories",
                "/v1/repositories/{repoId}",
                "/v1/repositories/{repoId}/ensure",
                "/v1/repositories/{repoId}/sync",
                "/v1/repositories/{repoId}/checkout",
                "/v1/repositories/{repoId}/entry-points",
                "/v1/analyses/call-graphs/outgoing",
                "/v1/analyses/call-graphs/incoming",
                "/v1/api-routes/lookup",
                "/v1/api-routes/suggest"));
        assertThat(paths.keySet()).doesNotContain(
                "/v1/analyses/call-graph",
                "/v1/analyses/call-graph/flatten",
                "/v1/analyses/call-graphs/expand");
        assertThat(map(paths.get("/v1/analyses/call-graphs/outgoing"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/analyses/call-graphs/incoming"))).containsOnlyKeys("post");
        assertResponseCodes(operation(paths, "/v1/analyses/call-graphs/outgoing", "post"),
                "200", "400", "401", "404", "409", "422", "500", "502", "503", "504");
        assertThat(operation(paths, "/v1/analyses/call-graphs/incoming", "post").get("operationId"))
                .isEqualTo("analyzeIncomingCallGraph");
        assertResponseCodes(operation(paths, "/v1/analyses/call-graphs/incoming", "post"),
                "200", "400", "401", "404", "409", "422", "500", "502", "503", "504");
        assertThat(map(map(operation(paths, "/v1/analyses/call-graphs/incoming", "post").get("responses"))
                .get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/IncomingCallGraph"));
        assertRequiredRequestBody(
                operation(paths, "/v1/analyses/call-graphs/outgoing", "post"),
                "#/components/schemas/AnalyzeOutgoingCallGraphRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/analyses/call-graphs/incoming", "post"),
                "#/components/schemas/AnalyzeIncomingCallGraphRequest");
    }

    @Test
    void should_describe_revision_bound_graph_requests_with_matching_shapes() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> request = schema(schemas, "AnalyzeOutgoingCallGraphRequest");
        Map<String, Object> incomingRequest = schema(schemas, "AnalyzeIncomingCallGraphRequest");
        assertThat(incomingRequest).isEqualTo(request);
        assertClosedObject(request);
        assertThat(required(request)).containsExactlyInAnyOrder("repoId", "expectedRevision", "target");
        Map<String, Object> requestProperties = properties(request);
        assertThat(requestProperties.keySet()).containsExactlyInAnyOrder(
                "repoId", "expectedRevision", "depth", "target");
        assertThat(schema(requestProperties, "expectedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(requestProperties, "depth"))
                .contains(entry("default", 2), entry("minimum", 1), entry("maximum", 2));
        assertThat(schema(requestProperties, "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));

        Map<String, Object> target = schema(schemas, "MethodTarget");
        assertClosedObject(target);
        assertThat(required(target)).containsExactly(
                "sourceFile", "packageName", "className", "methodName", "parameterTypes");
        Map<String, Object> targetProperties = properties(target);
        assertThat(targetProperties.keySet()).containsExactlyInAnyOrder(
                "sourceFile", "packageName", "className", "methodName", "parameterTypes");
        Map<String, Object> sourceFile = schema(targetProperties, "sourceFile");
        assertThat(sourceFile)
                .contains(entry("maxLength", 1024), entry("pattern", SOURCE_FILE_PATTERN));
        Pattern sourceFilePattern = Pattern.compile((String) sourceFile.get("pattern"));
        assertThat(sourceFilePattern.matcher("src/main/java/Main.java").matches()).isTrue();
        assertThat(sourceFilePattern.matcher("   ").matches()).isFalse();
        assertThat(sourceFilePattern.matcher("src/Main" + '\0' + ".java").matches()).isFalse();
        assertThat(schema(targetProperties, "className"))
                .contains(entry("maxLength", 255), entry("pattern", CLASS_NAME_PATTERN));
        assertThat(schema(targetProperties, "methodName"))
                .contains(entry("maxLength", 255), entry("pattern", METHOD_NAME_PATTERN));
        assertThat(schema(targetProperties, "className").get("description"))
                .asString().contains("Unicode", "server");
        assertThat(schema(targetProperties, "packageName")).containsOnly(entry("type", "string"));
        assertThat(schema(targetProperties, "parameterTypes"))
                .containsEntry("type", "array")
                .containsEntry("minItems", 0);
        assertThat(schema(schema(targetProperties, "parameterTypes"), "items"))
                .contains(entry("type", "string"), entry("minLength", 1), entry("pattern", ".*\\S.*"));
    }

    @Test
    void should_pin_normalized_graph_response_shapes_and_wire_semantics() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> response = schema(schemas, "OutgoingCallGraphResponse");
        Map<String, Object> incomingResponse = schema(schemas, "IncomingCallGraphResponse");
        assertClosedObject(response);
        assertClosedObject(incomingResponse);
        assertExactPropertiesAndRequired(response,
                "status", "analyzedRevision", "rootNodeId", "traversal", "nodes", "edges", "warnings", "errors");
        assertExactPropertiesAndRequired(incomingResponse,
                "status", "analyzedRevision", "rootNodeId", "traversal", "nodes", "edges", "warnings", "errors");
        assertThat(properties(incomingResponse)).isEqualTo(properties(response));
        assertThat(schema(properties(response), "status"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/OutgoingGraphStatus"));
        assertThat(list(schema(schemas, "OutgoingGraphStatus").get("enum"))).containsExactly("SUCCESS", "PARTIAL");
        assertThat(list(schema(schemas, "GraphContentState").get("enum")))
                .containsExactly("FULL_SOURCE", "TARGET_ONLY", "EXTERNAL");
        assertThat(list(schema(schemas, "GraphTraversalState").get("enum")))
                .containsExactly("EXPANDED", "DEPTH_BOUNDARY", "BUDGET_CUTOFF", "EXTERNAL");

        Map<String, Object> traversal = schema(schemas, "GraphTraversal");
        assertClosedObject(traversal);
        assertExactPropertiesAndRequired(traversal,
                "requestedDepth", "expandedNodeCount", "nodeBudget", "rootDirectCallsComplete", "limitReason");
        assertThat(schema(properties(traversal), "expandedNodeCount").get("description"))
                .asString().contains("depth-2", "source-expansion");
        assertThat(schema(properties(traversal), "nodeBudget").get("description"))
                .asString().contains("depth-2", "source-expansion");
        assertThat(schema(properties(traversal), "limitReason"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/GraphLimitReason"));
        assertThat(list(schema(schemas, "GraphLimitReason").get("enum"))).containsExactly("NONE", "NODE_BUDGET");

        Map<String, Object> node = schema(schemas, "GraphNode");
        assertClosedObject(node);
        assertExactProperties(node,
                "nodeId", "target", "externalSymbol", "contentState", "traversalState", "methodBody", "declarationRange");
        assertThat(required(node)).containsExactlyInAnyOrder("nodeId", "contentState", "traversalState");
        assertNullableReference(properties(node), "target", "MethodTarget");
        assertNullableString(properties(node), "externalSymbol");
        assertNullableString(properties(node), "methodBody");
        assertNullableReference(properties(node), "declarationRange", "SourceRange");
        assertThat(schema(properties(node), "contentState"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/GraphContentState"));
        assertThat(schema(properties(node), "traversalState"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/GraphTraversalState"));

        Map<String, Object> edge = schema(schemas, "GraphEdge");
        assertClosedObject(edge);
        assertExactPropertiesAndRequired(edge,
                "callerNodeId", "calleeNodeId", "callSite", "callExpression", "resolutionStrategy", "confidence", "evidence");
        assertThat(edge.get("description")).asString().contains("caller", "callee");
        assertRequiredNonNullableArray(edge, "evidence");

        Map<String, Object> warning = schema(schemas, "GraphWarning");
        assertClosedObject(warning);
        assertExactProperties(warning,
                "code", "message", "nodeId", "callExpression", "callSite", "candidates");
        assertThat(required(warning)).containsExactlyInAnyOrder("code", "message", "nodeId", "candidates");
        assertNullableString(properties(warning), "callExpression");
        assertNullableReference(properties(warning), "callSite", "SourceRange");
        assertRequiredNonNullableArray(warning, "candidates");
        assertThat(schema(schema(properties(warning), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));

        Map<String, Object> error = schema(schemas, "GraphError");
        assertClosedObject(error);
        assertExactPropertiesAndRequired(error, "code", "message", "nodeId");

        Map<String, Object> position = schema(schemas, "Position");
        assertClosedObject(position);
        assertThat(position.get("description")).asString().contains("Zero-based UTF-16");
        Map<String, Object> range = schema(schemas, "SourceRange");
        assertClosedObject(range);
        assertThat(range.get("description")).asString().contains("start-inclusive", "end-exclusive");
        assertExactPropertiesAndRequired(range, "sourceFile", "start", "end");

        assertRequiredNonNullableArray(response, "nodes");
        assertRequiredNonNullableArray(response, "edges");
        assertRequiredNonNullableArray(response, "warnings");
        assertRequiredNonNullableArray(response, "errors");

        assertThat(schemas.keySet()).noneMatch(name -> name.matches(".*(Flattened|Recursive|Cursor|Page|Session|AnalysisStatus).*"));
    }

    @Test
    void should_freeze_the_structured_error_contract() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> error = schema(schemas, "ApiErrorResponse");
        assertClosedObject(error);
        assertThat(properties(error).keySet()).containsExactlyInAnyOrder(
                "errorCode", "message", "repoId", "expectedRevision", "currentRevision", "target", "candidates", "requestId");
        assertThat(list(schema(properties(error), "errorCode").get("enum"))).containsExactly(
                "REQUEST_INVALID",
                "SEMANTIC_UNAUTHORIZED",
                "REPOSITORY_NOT_FOUND",
                "REPOSITORY_NOT_READY",
                "REPOSITORY_REVISION_MISMATCH",
                "SEMANTIC_BINDING_AMBIGUOUS",
                "SEMANTIC_TARGET_NOT_FOUND",
                "SEMANTIC_BINDING_UNRESOLVED",
                "SEMANTIC_PROTOCOL_ERROR",
                "SEMANTIC_ENGINE_START_FAILED",
                "SEMANTIC_REQUEST_TIMEOUT",
                "INTERNAL_ERROR");
        assertThat(required(error)).containsExactlyInAnyOrder("errorCode", "message", "candidates");
        assertRequiredNonNullableArray(error, "candidates");
    }

    @Test
    void should_keep_disabled_auth_outside_the_outgoing_error_taxonomy() {
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> responses = map(components.get("responses"));
        Map<String, Object> authDisabled = map(responses.get("AuthDisabled"));
        Map<String, Object> content = map(authDisabled.get("content"));
        Map<String, Object> json = map(content.get("application/json"));
        assertThat(map(json.get("schema")))
                .isEqualTo(Map.of("$ref", "#/components/schemas/AuthDisabledErrorResponse"));

        Map<String, Object> error = schema(schemas(), "AuthDisabledErrorResponse");
        assertClosedObject(error);
        assertThat(properties(error).keySet()).containsExactlyInAnyOrder(
                "errorCode", "message", "repoId", "expectedRevision", "currentRevision", "target", "candidates", "requestId");
        assertThat(list(schema(properties(error), "errorCode").get("enum")))
                .containsExactly("SEMANTIC_AUTH_DISABLED");
        assertThat(required(error)).containsExactlyInAnyOrder("errorCode", "message", "candidates");
        assertRequiredNonNullableArray(error, "candidates");
    }

    @Test
    void should_parse_nullable_composed_references_without_messages() {
        String resource = Objects.requireNonNull(
                getClass().getResource("/openapi/semantic-api-v1.yaml"),
                "OpenAPI document is required").toString();

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(resource, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
        OpenAPI openApi = result.getOpenAPI();
        assertNullableComposedReference(openApi, "GraphNode", "target", "MethodTarget");
        assertNullableComposedReference(openApi, "GraphNode", "declarationRange", "SourceRange");
        assertNullableComposedReference(openApi, "GraphWarning", "callSite", "SourceRange");
        assertNullableComposedReference(openApi, "ApiErrorResponse", "target", "MethodTarget");
    }

    @Test
    void should_give_incoming_and_outgoing_requests_identical_validation_and_conversion_contracts() throws Exception {
        assertRequestContract("com.java.semantic.api.dto.AnalyzeOutgoingCallGraphRequest");
        assertRequestContract("com.java.semantic.api.dto.AnalyzeIncomingCallGraphRequest");
    }

    private void assertRequestContract(String requestClassName) throws Exception {
        Class<?> requestType = Class.forName(requestClassName);
        Class<?> targetType = Class.forName("com.java.semantic.api.dto.MethodTargetRequest");
        Constructor<?> targetConstructor = targetType.getConstructor(
                String.class, String.class, String.class, String.class, List.class);
        Object target = targetConstructor.newInstance(
                "src/main/java/com/example/OrderController.java",
                "",
                "OrderController",
                "placeOrder",
                List.of("com.example.PlaceOrderRequest"));
        Constructor<?> requestConstructor = requestType.getConstructor(
                String.class, String.class, Integer.class, targetType);
        Object request = requestConstructor.newInstance("order-service", "FIXTURE", null, target);

        Method depth = requestType.getMethod("depth");
        Method toDomain = targetType.getMethod("toDomain");
        assertThat(depth.invoke(request)).isEqualTo(2);
        assertThat(toDomain.invoke(target)).isEqualTo(new MethodTarget(
                "src/main/java/com/example/OrderController.java",
                "",
                "OrderController",
                "placeOrder",
                List.of("com.example.PlaceOrderRequest")));

        Object invalidTarget = targetConstructor.newInstance(
                "",
                "",
                "",
                "",
                List.of(" "));
        Object invalidRequest = requestConstructor.newInstance("", "bad", 3, invalidTarget);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<Object>> violations = validator.validate(invalidRequest);
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "repoId",
                        "expectedRevision",
                        "depth",
                        "target.sourceFile",
                        "target.className",
                        "target.methodName",
                        "target.parameterTypes[0].<list element>");

        assertThatThrownBy(() -> targetConstructor.newInstance(
                "src/main/java/com/example/OrderController.java",
                null,
                "OrderController",
                "placeOrder",
                List.of()))
                .hasCauseInstanceOf(NullPointerException.class)
                .hasRootCauseMessage("packageName is required");

        ObjectMapper objectMapper = new ObjectMapper();
        assertThatThrownBy(() -> objectMapper.readValue("""
                {
                  "repoId":"order-service",
                  "expectedRevision":"FIXTURE",
                  "target":{
                    "sourceFile":"src/main/java/com/example/OrderController.java",
                    "packageName":"",
                    "className":"OrderController",
                    "methodName":"placeOrder",
                    "parameterTypes":[]
                  },
                  "unknown":true
                }
                """, requestType))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("unknown request property");
    }

    @Test
    void should_reject_invalid_source_coordinates_and_ranges() {
        assertThatThrownBy(() -> new PositionResponse(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("line must not be negative");
        assertThatThrownBy(() -> new PositionResponse(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("character must not be negative");
        PositionResponse position = new PositionResponse(0, 0);
        assertThatThrownBy(() -> new SourceRangeResponse(null, position, position))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceFile must not be blank");
        assertThatThrownBy(() -> new SourceRangeResponse("src/Main.java", null, position))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("start is required");
        assertThatThrownBy(() -> new SourceRangeResponse("src/Main.java", position, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("end is required");
    }

    @Test
    void should_accept_only_non_null_outgoing_graph_limit_reasons() {
        assertThat(new GraphTraversalResponse(2, 1, 10, true, "NONE").limitReason()).isEqualTo("NONE");
        assertThat(new GraphTraversalResponse(2, 1, 10, true, "NODE_BUDGET").limitReason())
                .isEqualTo("NODE_BUDGET");
        assertThatThrownBy(() -> new GraphTraversalResponse(2, 1, 10, true, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("limitReason is required");
        assertThatThrownBy(() -> new GraphTraversalResponse(2, 1, 10, true, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limitReason must be NONE or NODE_BUDGET");
    }

    @Test
    void should_defensively_copy_graph_wire_lists_and_guard_required_values() {
        List<String> parameterTypes = new ArrayList<>();
        MethodTargetRequest target = new MethodTargetRequest(
                "src/main/java/com/example/OrderController.java",
                "com.example",
                "OrderController",
                "placeOrder",
                parameterTypes);
        parameterTypes.add("java.lang.String");
        assertThat(target.parameterTypes()).isEmpty();
        assertThatThrownBy(() -> new MethodTargetRequest(
                "src/main/java/com/example/OrderController.java",
                "com.example",
                "OrderController",
                "placeOrder",
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("parameterTypes are required");
        SourceRangeResponse range = new SourceRangeResponse(
                "src/main/java/com/example/OrderController.java",
                new PositionResponse(0, 0),
                new PositionResponse(0, 1));
        List<String> evidence = new ArrayList<>();
        GraphEdgeResponse edge = new GraphEdgeResponse("caller", "callee", range, "call()", "JDT", 1.0, evidence);
        evidence.add("mutated");
        assertThat(edge.evidence()).isEmpty();
        assertThatThrownBy(() -> new GraphEdgeResponse("caller", "callee", range, "call()", "JDT", 1.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("evidence is required");

        List<MethodTargetResponse> candidates = new ArrayList<>();
        GraphWarningResponse warning = new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, candidates);
        candidates.add(new MethodTargetResponse(
                "src/main/java/com/example/OrderController.java",
                "com.example",
                "OrderController",
                "placeOrder",
                List.of()));
        assertThat(warning.candidates()).isEmpty();
        assertThatThrownBy(() -> new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("candidates are required");

        GraphNodeResponse node = new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", null, null);
        List<GraphNodeResponse> nodes = new ArrayList<>();
        List<GraphEdgeResponse> edges = new ArrayList<>(List.of(edge));
        List<GraphWarningResponse> warnings = new ArrayList<>(List.of(warning));
        List<GraphErrorResponse> errors = new ArrayList<>(List.of(new GraphErrorResponse("CHILD_FAILURE", "failure", "node")));
        OutgoingCallGraphResponse response = new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                nodes,
                edges,
                warnings,
                errors);
        nodes.add(node);
        edges.add(edge);
        warnings.add(warning);
        errors.add(new GraphErrorResponse("CHILD_FAILURE", "failure", "node"));
        assertThat(response.nodes()).isEmpty();
        assertThat(response.edges()).containsExactly(edge);
        assertThat(response.warnings()).containsExactly(warning);
        assertThat(response.errors()).hasSize(1);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                null,
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nodes are required");
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                null,
                List.of(),
                List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("edges are required");
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                null,
                List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("warnings are required");
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                List.of(),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("errors are required");

        IncomingCallGraphResponse incomingResponse = new IncomingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertThat(incomingResponse.getClass()).isNotEqualTo(response.getClass());
        assertThat(incomingResponse.nodes()).hasSize(0);

        assertThat(new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", null, null).target()).isNull();
        assertThat(new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, List.of()).callExpression())
                .isNull();
        assertThat(new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, List.of()).callSite())
                .isNull();
    }

    @Test
    void should_preserve_repository_and_route_contract_paths() {
        Map<String, Object> paths = map(document.get("paths"));
        assertThat(map(paths.get("/v1/repositories"))).containsOnlyKeys("get");
        assertThat(map(paths.get("/v1/repositories/{repoId}"))).containsOnlyKeys("parameters", "get");
        assertThat(map(paths.get("/v1/repositories/{repoId}/ensure")))
                .containsOnlyKeys("parameters", "post");
        assertThat(map(paths.get("/v1/repositories/{repoId}/sync")))
                .containsOnlyKeys("parameters", "post");
        assertThat(map(paths.get("/v1/repositories/{repoId}/checkout")))
                .containsOnlyKeys("parameters", "post");
        assertThat(map(paths.get("/v1/repositories/{repoId}/entry-points")))
                .containsOnlyKeys("parameters", "get");
        assertThat(map(paths.get("/v1/api-routes/lookup"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/api-routes/suggest"))).containsOnlyKeys("post");

        assertResponseCodes(operation(paths, "/v1/repositories", "get"), "200", "401", "403", "409");
        assertResponseCodes(operation(paths, "/v1/repositories/{repoId}", "get"),
                "200", "400", "401", "403", "404", "409");
        assertResponseCodes(operation(paths, "/v1/repositories/{repoId}/ensure", "post"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/repositories/{repoId}/sync", "post"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/repositories/{repoId}/checkout", "post"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/repositories/{repoId}/entry-points", "get"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/api-routes/lookup", "post"), "200", "400", "401", "403", "500");
        assertResponseCodes(operation(paths, "/v1/api-routes/suggest", "post"), "200", "400", "401", "403", "500");
    }

    @Test
    void should_require_preserved_request_bodies_and_entry_point_types_query() {
        Map<String, Object> paths = map(document.get("paths"));
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/sync", "post"),
                "#/components/schemas/SyncRepositoryRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/checkout", "post"),
                "#/components/schemas/CheckoutRepositoryRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/api-routes/lookup", "post"),
                "#/components/schemas/ApiRouteLookupRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/api-routes/suggest", "post"),
                "#/components/schemas/ApiRouteSuggestRequest");

        Map<String, Object> entryPointOperation = operation(
                paths, "/v1/repositories/{repoId}/entry-points", "get");
        List<Object> parameters = list(entryPointOperation.get("parameters"));
        Map<String, Object> types = parameters.stream()
                .map(this::map)
                .filter(parameter -> "types".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(types).containsEntry("in", "query").containsEntry("required", Boolean.FALSE);
        assertThat(map(types.get("schema")))
                .containsEntry("type", "string")
                .containsEntry("pattern", "^(API|MQ|SCHEDULE)(,(API|MQ|SCHEDULE))*$");
    }

    @Test
    void should_preserve_global_security_and_route_request_schemas() {
        List<Object> security = list(document.get("security"));
        assertThat(security).hasSize(1);
        Map<String, Object> securityRequirement = map(security.getFirst());
        assertThat(securityRequirement).containsOnlyKeys("ApiToken");
        assertThat(list(securityRequirement.get("ApiToken"))).isEmpty();

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemes = map(components.get("securitySchemes"));
        assertThat(schemes).containsOnlyKeys("ApiToken");
        assertThat(map(schemes.get("ApiToken"))).containsOnly(
                entry("type", "apiKey"),
                entry("in", "header"),
                entry("name", "X-Api-Token"));

        Map<String, Object> schemas = schemas();
        Map<String, Object> lookupRequest = schema(schemas, "ApiRouteLookupRequest");
        assertThat(required(lookupRequest)).containsExactly("apiPath");
        assertExactProperties(lookupRequest, "apiPath", "httpMethod", "repoScope");
        assertNonBlankString(properties(lookupRequest), "apiPath");
        assertNullableString(properties(lookupRequest), "httpMethod");
        assertNullableString(properties(lookupRequest), "repoScope");

        Map<String, Object> suggestRequest = schema(schemas, "ApiRouteSuggestRequest");
        assertThat(required(suggestRequest)).containsExactly("apiPath", "limit");
        assertExactProperties(suggestRequest, "apiPath", "httpMethod", "repoScope", "limit");
        assertNonBlankString(properties(suggestRequest), "apiPath");
        assertNullableString(properties(suggestRequest), "httpMethod");
        assertNullableString(properties(suggestRequest), "repoScope");
        assertThat(schema(properties(suggestRequest), "limit"))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 20);
    }

    @Test
    void should_preserve_route_candidate_and_entry_point_response_shapes() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> candidate = schema(schemas, "ApiRouteCandidateResponse");
        assertExactPropertiesAndRequired(candidate,
                "repoId", "analyzedRevision", "httpMethod", "routeTemplate",
                "packageName", "className", "methodName");
        assertThat(schema(properties(candidate), "analyzedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);

        Map<String, Object> routeResponse = schema(schemas, "ApiRouteCandidatesResponse");
        assertExactPropertiesAndRequired(routeResponse, "candidates");
        assertThat(schema(schema(properties(routeResponse), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ApiRouteCandidateResponse"));

        Map<String, Object> method = schema(schemas, "EntryPointMethodResponse");
        assertThat(method.get("oneOf")).isEqualTo(List.of(
                Map.of("$ref", "#/components/schemas/ApiEntryPointMethodResponse"),
                Map.of("$ref", "#/components/schemas/MqEntryPointMethodResponse"),
                Map.of("$ref", "#/components/schemas/ScheduleEntryPointMethodResponse")));
        assertThat(map(method.get("discriminator"))).containsEntry("propertyName", "type");
        assertThat(map(map(method.get("discriminator")).get("mapping"))).containsExactly(
                entry("API", "#/components/schemas/ApiEntryPointMethodResponse"),
                entry("MQ", "#/components/schemas/MqEntryPointMethodResponse"),
                entry("SCHEDULE", "#/components/schemas/ScheduleEntryPointMethodResponse"));

        Map<String, Object> entryPoint = schema(schemas, "EntryPointClassResponse");
        assertExactProperties(entryPoint,
                "className", "packageName", "packagePath", "description", "basePaths", "methods");
        assertThat(schema(schema(properties(entryPoint), "methods"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/EntryPointMethodResponse"));
        assertThat(properties(entryPoint)).doesNotContainKeys(
                "sourceRoot", "path", "uri", "sourceText", "annotations", "sql");
    }

    @Test
    void should_preserve_safe_repository_schemas_and_repo_id_parameter() {
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = schemas();
        Map<String, Object> status = schema(schemas, "RepositoryStatusResponse");
        assertThat(required(status)).containsExactlyInAnyOrder("repoId", "mode", "displayName", "cloned");
        assertExactProperties(status,
                "repoId", "mode", "displayName", "currentBranch", "currentRevision", "cloned");
        assertThat(properties(status)).doesNotContainKeys(
                "sourceRoot", "path", "url", "credential", "git", "uri");
        assertNullableString(properties(status), "currentBranch");
        assertThat(schema(properties(status), "currentRevision"))
                .containsEntry("nullable", Boolean.TRUE)
                .containsEntry("pattern", REVISION_PATTERN);

        Map<String, Object> checkout = schema(schemas, "CheckoutRepositoryRequest");
        assertThat(required(checkout)).containsExactly("revision");
        assertNonBlankString(properties(checkout), "revision");

        Map<String, Object> repoId = schema(map(components.get("parameters")), "RepoId");
        assertThat(schema(repoId, "schema"))
                .containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
    }

    private Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
        return map(map(paths.get(path)).get(method));
    }

    private Map<String, Object> schemas() {
        return map(map(document.get("components")).get("schemas"));
    }

    private Map<String, Object> schema(Map<String, Object> schemas, String name) {
        return map(schemas.get(name));
    }

    private Map<String, Object> properties(Map<String, Object> schema) {
        return map(schema.get("properties"));
    }

    private List<Object> required(Map<String, Object> schema) {
        return list(schema.get("required"));
    }

    private void assertExactPropertiesAndRequired(Map<String, Object> schema, String... names) {
        assertExactProperties(schema, names);
        assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(List.of(names));
    }

    private void assertExactProperties(Map<String, Object> schema, String... names) {
        assertThat(properties(schema).keySet()).containsExactlyInAnyOrderElementsOf(List.of(names));
    }

    private void assertRequiredNonNullableArray(Map<String, Object> schema, String name) {
        assertThat(required(schema)).contains(name);
        assertThat(this.schema(properties(schema), name))
                .containsEntry("type", "array")
                .doesNotContainKey("nullable");
    }

    private void assertClosedObject(Map<String, Object> schema) {
        assertThat(schema).contains(entry("type", "object"), entry("additionalProperties", Boolean.FALSE));
    }

    private void assertNullableReference(Map<String, Object> properties, String name, String schemaName) {
        Map<String, Object> nullableReference = schema(properties, name);
        assertThat(nullableReference).containsOnlyKeys("nullable", "allOf");
        assertThat(nullableReference).containsEntry("nullable", Boolean.TRUE);
        assertThat(list(nullableReference.get("allOf")))
                .containsExactly(Map.of("$ref", "#/components/schemas/" + schemaName));
    }

    private void assertNullableString(Map<String, Object> properties, String name) {
        assertThat(schema(properties, name)).contains(
                entry("type", "string"),
                entry("nullable", Boolean.TRUE));
    }

    private void assertNonBlankString(Map<String, Object> properties, String name) {
        assertThat(schema(properties, name)).contains(
                entry("type", "string"),
                entry("minLength", 1),
                entry("pattern", ".*\\S.*"));
    }

    private void assertNullableComposedReference(
            OpenAPI openApi,
            String ownerSchemaName,
            String propertyName,
            String referencedSchemaName) {
        Schema<?> ownerSchema = openApi.getComponents().getSchemas().get(ownerSchemaName);
        Schema<?> propertySchema = ownerSchema.getProperties().get(propertyName);
        assertThat(propertySchema).isInstanceOf(ComposedSchema.class);
        assertThat(propertySchema.getNullable()).isTrue();
        assertThat(propertySchema.getAllOf())
                .singleElement()
                .extracting(Schema::get$ref)
                .isEqualTo("#/components/schemas/" + referencedSchemaName);
    }

    private void assertResponseCodes(Map<String, Object> operation, String... responseCodes) {
        assertThat(map(operation.get("responses")).keySet()).containsExactlyInAnyOrder(responseCodes);
    }

    private void assertRequiredRequestBody(Map<String, Object> operation, String expectedReference) {
        Map<String, Object> requestBody = map(operation.get("requestBody"));
        assertThat(requestBody).containsEntry("required", Boolean.TRUE);
        Map<String, Object> content = map(requestBody.get("content"));
        assertThat(content).containsOnlyKeys("application/json");
        Map<String, Object> mediaType = map(content.get("application/json"));
        assertThat(map(mediaType.get("schema"))).containsOnly(entry("$ref", expectedReference));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
