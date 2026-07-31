package com.java.semantic.api;

import com.java.semantic.identity.MethodTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
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
import com.java.semantic.syntax.application.ConceptKind;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final String EVENT_TYPE_PATTERN = "^(?:[\\p{L}_][\\p{L}\\p{N}_]*\\.)+[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[\\])*$";

    private Map<String, Object> document;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                "/v1/api-routes/suggest",
                "/v1/discovery/event-listeners",
                "/v1/discovery/method-implementations",
                "/v1/discovery/concepts",
                "/v1/discovery/type-members",
                "/v1/discovery/method-source",
                "/v1/discovery/method-source-segment",
                "/v1/discovery/method-sql",
                "/v1/discovery/method-sql-segment",
                "/v1/discovery/mapper-sql-fragment",
                "/v1/discovery/mapper-fragment-segment"));
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
    void should_describe_closed_revision_bound_structured_concept_discovery_contract() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertThat(document.get("security")).isEqualTo(List.of(Map.of("ApiToken", List.of())));
        assertThat(map(paths.get("/v1/discovery/concepts"))).containsOnlyKeys("post");
        Map<String, Object> operation = operation(paths, "/v1/discovery/concepts", "post");
        assertThat(operation).doesNotContainKey("security");
        assertThat(operation.get("operationId")).isEqualTo("discoverConcepts");
        assertRequiredRequestBody(operation, "#/components/schemas/DiscoverConceptsRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "422", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/ConceptDiscovery"));
        assertThat(map(operation.get("responses"))).doesNotContainKey("413");

        Map<String, Object> request = schema(schemas, "DiscoverConceptsRequest");
        assertClosedObject(request);
        assertExactProperties(request, "operator", "repoId", "expectedRevision", "terms", "kinds", "packagePrefix", "offset", "limit");
        assertThat(required(request)).containsExactlyInAnyOrder("operator", "repoId", "expectedRevision", "terms", "kinds");
        assertThat(list(schema(properties(request), "operator").get("enum"))).containsExactly("ALL");
        assertThat(schema(properties(request), "expectedRevision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(request), "terms")).contains(
                entry("type", "array"), entry("minItems", 1), entry("maxItems", 4),
                entry("items", Map.of("$ref", "#/components/schemas/ConceptSearchTerm")));
        assertThat(schema(properties(request), "kinds")).contains(
                entry("type", "array"), entry("minItems", 1), entry("uniqueItems", Boolean.TRUE),
                entry("items", Map.of("$ref", "#/components/schemas/ConceptKind")));
        assertThat(schema(properties(request), "kinds")).doesNotContainKey("maxItems");
        assertThat(schema(properties(request), "packagePrefix")).contains(
                entry("type", "string"), entry("maxLength", 255));
        assertThat(schema(properties(request), "offset")).contains(entry("default", 0), entry("minimum", 0));
        assertThat(schema(properties(request), "limit")).contains(
                entry("default", 50), entry("minimum", 1), entry("maximum", 100));

        Map<String, Object> term = schema(schemas, "ConceptSearchTerm");
        assertClosedObject(term);
        assertExactPropertiesAndRequired(term, "value", "matchMode");
        assertThat(schema(properties(term), "value")).contains(entry("minLength", 2), entry("maxLength", 128));
        assertThat(schema(properties(term), "matchMode"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptMatchMode"));
        List<String> runtimeConceptKinds = Arrays.stream(ConceptKind.values()).map(Enum::name).toList();
        assertThat(list(schema(schemas, "ConceptKind").get("enum")))
                .containsExactlyElementsOf(runtimeConceptKinds)
                .contains("MAPPER_STATEMENT");
        assertThat(list(schema(schemas, "ConceptMatchMode").get("enum")))
                .containsExactly("TOKEN_EXACT", "TOKEN_PREFIX", "CANONICAL_EXACT");

        Map<String, Object> response = schema(schemas, "DiscoverConceptsResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "normalizedTerms", "searchedKinds", "supportedKinds", "limitations",
                "candidates", "page", "coverage", "issueSummaries", "availableFollowUps", "unavailableFollowUps");
        assertThat(schema(properties(response), "supportedKinds"))
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ConceptKind"));
        assertThat(schema(properties(response), "page"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptPageResponse"));
        assertThat(schema(properties(response), "coverage"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCoverageResponse"));

        Map<String, Object> candidate = schema(schemas, "ConceptCandidateResponse");
        assertClosedObject(candidate);
        assertExactProperties(candidate,
                "kind", "canonicalValue", "displayValue", "matchedTerms", "packageName", "declaringType", "authority",
                "subject", "target", "mapperStatementMapping", "evidence", "availableFollowUps");
        assertThat(required(candidate)).containsExactlyInAnyOrder(
                "kind", "canonicalValue", "displayValue", "matchedTerms", "packageName", "authority", "evidence", "availableFollowUps");
        assertThat(schema(properties(candidate), "kind"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptKind"));
        assertThat(schema(schema(properties(candidate), "availableFollowUps"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateFollowUpResponse"));
        assertThat(schema(properties(candidate), "mapperStatementMapping"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MapperStatementMappingResponse"));

        Map<String, Object> mapperMapping = schema(schemas, "MapperStatementMappingResponse");
        assertThat(mapperMapping).containsOnlyKeys("description", "oneOf", "discriminator");
        assertThat(list(mapperMapping.get("oneOf"))).containsExactly(
                Map.of("$ref", "#/components/schemas/ResolvedMapperStatementMappingResponse"),
                Map.of("$ref", "#/components/schemas/AmbiguousMapperStatementMappingResponse"),
                Map.of("$ref", "#/components/schemas/UnresolvedMapperStatementMappingResponse"));
        assertThat(map(schema(mapperMapping, "discriminator").get("mapping"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "RESOLVED", "#/components/schemas/ResolvedMapperStatementMappingResponse",
                "AMBIGUOUS", "#/components/schemas/AmbiguousMapperStatementMappingResponse",
                "UNRESOLVED", "#/components/schemas/UnresolvedMapperStatementMappingResponse"));
        assertThat(schema(mapperMapping, "discriminator")).containsEntry("propertyName", "status");

        assertMapperMappingStateSchema(
                schemas,
                "ResolvedMapperStatementMappingResponse",
                "RESOLVED",
                "MapperSqlMethodCandidateResponse",
                1,
                Optional.of(1),
                false);
        assertMapperMappingStateSchema(
                schemas,
                "AmbiguousMapperStatementMappingResponse",
                "AMBIGUOUS",
                "MapperSourceMethodCandidateResponse",
                2,
                Optional.empty(),
                false);
        assertMapperMappingStateSchema(
                schemas,
                "UnresolvedMapperStatementMappingResponse",
                "UNRESOLVED",
                "MapperSourceMethodCandidateResponse",
                0,
                Optional.empty(),
                true);
        assertMapperMethodCandidateSchema(
                schemas,
                "MapperSqlMethodCandidateResponse",
                "GET_METHOD_SQL",
                "/v1/discovery/method-sql",
                "getMethodSql");
        assertMapperMethodCandidateSchema(
                schemas,
                "MapperSourceMethodCandidateResponse",
                "GET_METHOD_SOURCE",
                "/v1/discovery/method-source",
                "getMethodSource");

        Map<String, Object> evidence = schema(schemas, "ConceptEvidenceResponse");
        assertClosedObject(evidence);
        assertExactProperties(evidence,
                "kind", "subject", "target", "resourcePath", "databaseId", "documentOrdinal", "representation");
        assertThat(required(evidence)).containsExactly("kind");
        assertThat(list(schema(properties(evidence), "representation").get("enum")))
                .containsExactly("MAPPER_XML_ELEMENT", "ANNOTATION_SQL_TEXT");

        assertPageCoverageAndFollowUpSchemas(schemas);
    }

    @Test
    void should_accept_both_method_follow_up_operations_through_one_shared_request_shape() throws Exception {
        Map<String, Object> sourceRequest = methodFollowUpRequest("src/main/java/com/example/OrderMapper.java");
        Map<String, Object> sqlRequest = methodFollowUpRequest("src/main/java/com/example/OrderMapper.java");

        assertOpenApiPayloadValid("ConceptCandidateFollowUpRequestResponse", sourceRequest);
        assertOpenApiPayloadValid("ConceptCandidateFollowUpRequestResponse", sqlRequest);
    }

    @Test
    void should_validate_mapper_mapping_payloads_against_status_specific_shapes() throws Exception {
        Map<String, Object> targetA = methodTargetPayload("src/main/java/com/example/OrderMapper.java", "java.lang.String");
        Map<String, Object> targetB = methodTargetPayload("src/main/java/com/example/ArchiveMapper.java", "long");
        Map<String, Object> sourceCandidateA = mapperCandidate(
                targetA,
                mapperFollowUp("GET_METHOD_SOURCE", "/v1/discovery/method-source", "getMethodSource", targetA));
        Map<String, Object> sourceCandidateB = mapperCandidate(
                targetB,
                mapperFollowUp("GET_METHOD_SOURCE", "/v1/discovery/method-source", "getMethodSource", targetB));
        Map<String, Object> sqlCandidate = mapperCandidate(
                targetA,
                mapperFollowUp("GET_METHOD_SQL", "/v1/discovery/method-sql", "getMethodSql", targetA));

        assertOpenApiPayloadValid("MapperStatementMappingResponse", mapperMapping("RESOLVED", List.of(sqlCandidate)));
        assertOpenApiPayloadValid(
                "MapperStatementMappingResponse",
                mapperMapping("AMBIGUOUS", List.of(sourceCandidateA, sourceCandidateB)));
        assertOpenApiPayloadValid(
                "MapperStatementMappingResponse",
                unresolvedMapperMapping(List.of(sourceCandidateA)));

        assertOpenApiPayloadInvalid(
                "MapperStatementMappingResponse",
                mapperMapping("RESOLVED", List.of(sourceCandidateA)));
        assertOpenApiPayloadInvalid(
                "MapperStatementMappingResponse",
                mapperMapping("AMBIGUOUS", List.of(sourceCandidateA)));
        assertOpenApiPayloadInvalid(
                "MapperStatementMappingResponse",
                mapperMapping("AMBIGUOUS", List.of(sourceCandidateA, sqlCandidate)));
        assertOpenApiPayloadInvalid(
                "MapperStatementMappingResponse",
                Map.of(
                        "namespace", "com.example.OrderMapper",
                        "statementId", "findOrders",
                        "status", "UNRESOLVED",
                        "candidates", List.of(sqlCandidate)));
    }

    @Test
    void should_describe_closed_revision_bound_type_member_discovery_contract() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertThat(map(paths.get("/v1/discovery/type-members"))).containsOnlyKeys("post");
        Map<String, Object> operation = operation(paths, "/v1/discovery/type-members", "post");
        assertThat(operation).doesNotContainKey("security");
        assertThat(operation.get("operationId")).isEqualTo("discoverTypeMembers");
        assertRequiredRequestBody(operation, "#/components/schemas/DiscoverTypeMembersRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/TypeMemberDiscovery"));

        Map<String, Object> request = schema(schemas, "DiscoverTypeMembersRequest");
        assertClosedObject(request);
        assertExactProperties(request,
                "repoId", "expectedRevision", "sourceFile", "fullyQualifiedName", "memberKinds", "namePrefix", "offset", "limit");
        assertThat(required(request)).containsExactlyInAnyOrder(
                "repoId", "expectedRevision", "sourceFile", "fullyQualifiedName", "memberKinds");
        assertThat(schema(properties(request), "fullyQualifiedName")).containsEntry("maxLength", 1024);
        assertThat(schema(properties(request), "memberKinds")).contains(
                entry("type", "array"), entry("minItems", 1),
                entry("items", Map.of("$ref", "#/components/schemas/TypeMemberKind")));
        assertThat(list(schema(schemas, "TypeMemberKind").get("enum"))).containsExactly("METHOD", "FIELD");

        Map<String, Object> response = schema(schemas, "DiscoverTypeMembersResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "sourceFile", "fullyQualifiedName", "typeKind", "annotations",
                "implementedTypes", "extendedTypes", "members", "page", "coverage", "availableFollowUps");
        assertThat(schema(schema(properties(response), "members"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/TypeMemberResponse"));

        Map<String, Object> member = schema(schemas, "TypeMemberResponse");
        assertThat(member).containsOnlyKeys("oneOf", "discriminator");
        assertThat(member.get("discriminator")).isEqualTo(Map.of(
                "propertyName", "kind",
                "mapping", Map.of(
                        "METHOD", "#/components/schemas/MethodTypeMemberResponse",
                        "FIELD", "#/components/schemas/FieldTypeMemberResponse")));
        assertThat(list(member.get("oneOf"))).containsExactly(
                Map.of("$ref", "#/components/schemas/MethodTypeMemberResponse"),
                Map.of("$ref", "#/components/schemas/FieldTypeMemberResponse"));
    }

    @Test
    void should_describe_only_closed_typed_exact_content_and_stateless_segment_contracts() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertExactContentOperation(
                paths,
                "/v1/discovery/method-source",
                "getMethodSource",
                "GetMethodSourceRequest",
                "ExactContent");
        assertExactContentOperation(
                paths,
                "/v1/discovery/method-sql",
                "getMethodSql",
                "GetMapperStatementRequest",
                "ExactContent");
        assertExactContentOperation(
                paths,
                "/v1/discovery/mapper-sql-fragment",
                "getMapperFragment",
                "GetMapperFragmentRequest",
                "ExactContent");
        assertExactContentOperation(
                paths,
                "/v1/discovery/method-source-segment",
                "getMethodSourceSegment",
                "GetMethodSourceSegmentRequest",
                "ExactContentSegment");
        assertExactContentOperation(
                paths,
                "/v1/discovery/method-sql-segment",
                "getMethodSqlSegment",
                "GetMapperStatementSegmentRequest",
                "ExactContentSegment");
        assertExactContentOperation(
                paths,
                "/v1/discovery/mapper-fragment-segment",
                "getMapperFragmentSegment",
                "GetMapperFragmentSegmentRequest",
                "ExactContentSegment");
        assertThat(paths.keySet()).doesNotContain(
                "/v1/discovery/content",
                "/v1/discovery/content-segment",
                "/v1/discovery/literal-search");

        assertClosedExactRequest(schemas, "GetMethodSourceRequest", "target");
        assertClosedExactRequest(schemas, "GetMapperStatementRequest", "target");
        assertClosedExactRequest(schemas, "GetMapperFragmentRequest", "fragmentIdentity");
        assertClosedExactSegmentRequest(schemas, "GetMethodSourceSegmentRequest", "target");
        assertClosedExactSegmentRequest(schemas, "GetMapperStatementSegmentRequest", "target");
        assertClosedExactSegmentRequest(schemas, "GetMapperFragmentSegmentRequest", "fragmentIdentity");

        Map<String, Object> target = schema(schemas, "MethodTarget");
        assertClosedObject(target);
        assertExactPropertiesAndRequired(
                target, "sourceFile", "packageName", "className", "methodName", "parameterTypes");

        Map<String, Object> fragmentRequest = schema(schemas, "MapperFragmentIdentityRequest");
        assertClosedObject(fragmentRequest);
        assertExactPropertiesAndRequired(
                fragmentRequest,
                "namespace",
                "fragmentId",
                "resourcePath",
                "documentOrdinal",
                "representation");
        assertThat(list(schema(properties(fragmentRequest), "representation").get("enum")))
                .containsExactly("MAPPER_XML_ELEMENT");

        Map<String, Object> response = schema(schemas, "ExactContentResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response, "repoId", "analyzedRevision", "variants");
        assertThat(schema(schema(properties(response), "variants"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ExactContentVariantResponse"));

        Map<String, Object> variant = schema(schemas, "ExactContentVariantResponse");
        assertClosedObject(variant);
        assertExactProperties(
                variant,
                "statementIdentity",
                "fragmentIdentity",
                "content",
                "contentRef",
                "utf8ByteCount",
                "segmentCount",
                "includeResolutions",
                "availableFollowUps");
        assertThat(required(variant)).containsExactlyInAnyOrder(
                "utf8ByteCount", "segmentCount", "includeResolutions", "availableFollowUps");
        List<Object> invariantGroups = list(variant.get("allOf"));
        assertThat(invariantGroups).hasSize(2);
        List<Object> identityBranches = list(map(invariantGroups.get(0)).get("oneOf"));
        assertThat(identityBranches).containsExactly(
                Map.of(
                        "not", Map.of("anyOf", List.of(
                                Map.of("required", List.of("statementIdentity")),
                                Map.of("required", List.of("fragmentIdentity")))),
                        "properties", Map.of(
                                "includeResolutions", Map.of("maxItems", 0))),
                Map.of(
                        "required", List.of("statementIdentity"),
                        "not", Map.of("required", List.of("fragmentIdentity"))),
                Map.of(
                        "required", List.of("fragmentIdentity"),
                        "not", Map.of("required", List.of("statementIdentity")),
                        "properties", Map.of(
                                "includeResolutions", Map.of("maxItems", 0))));
        List<Object> contentBranches = list(map(invariantGroups.get(1)).get("oneOf"));
        assertThat(contentBranches).containsExactly(
                Map.of(
                        "required", List.of("content"),
                        "not", Map.of("required", List.of("contentRef")),
                        "properties", Map.of(
                                "segmentCount", Map.of("enum", List.of(0)),
                                "availableFollowUps", Map.of("maxItems", 0))),
                Map.of(
                        "required", List.of("contentRef"),
                        "not", Map.of("required", List.of("content")),
                        "properties", Map.of(
                                "segmentCount", Map.of("minimum", 1),
                                "availableFollowUps", Map.of("minItems", 1, "maxItems", 1))));
        assertThat(schema(schema(properties(variant), "includeResolutions"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MapperIncludeResolutionResponse"));
        assertThat(schema(schema(properties(variant), "availableFollowUps"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpResponse"));

        Map<String, Object> includeResolution = schema(schemas, "MapperIncludeResolutionResponse");
        assertClosedObject(includeResolution);
        assertExactPropertiesAndRequired(
                includeResolution, "refId", "status", "availableFollowUps");
        assertThat(list(schema(properties(includeResolution), "status").get("enum")))
                .containsExactly("RESOLVED", "AMBIGUOUS", "UNRESOLVED");
        assertThat(schema(schema(properties(includeResolution), "availableFollowUps"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpResponse"));

        Map<String, Object> statement = schema(schemas, "MapperStatementIdentityResponse");
        assertClosedObject(statement);
        assertExactProperties(
                statement,
                "namespace",
                "statementId",
                "resourcePath",
                "databaseId",
                "documentOrdinal",
                "representation");
        assertThat(required(statement)).containsExactlyInAnyOrder(
                "namespace", "statementId", "resourcePath", "documentOrdinal", "representation");
        assertThat(list(schema(properties(statement), "representation").get("enum")))
                .containsExactly("MAPPER_XML_ELEMENT", "ANNOTATION_SQL_TEXT");

        Map<String, Object> fragment = schema(schemas, "MapperFragmentIdentityResponse");
        assertClosedObject(fragment);
        assertExactPropertiesAndRequired(
                fragment,
                "namespace",
                "fragmentId",
                "resourcePath",
                "documentOrdinal",
                "representation");
        assertThat(list(schema(properties(fragment), "representation").get("enum")))
                .containsExactly("MAPPER_XML_ELEMENT");

        Map<String, Object> segment = schema(schemas, "ExactContentSegmentResponse");
        assertClosedObject(segment);
        assertExactProperties(
                segment,
                "contentRef",
                "segmentIndex",
                "segmentCount",
                "utf8ByteCount",
                "content",
                "nextSegmentFollowUp");
        assertThat(required(segment)).containsExactlyInAnyOrder(
                "contentRef", "segmentIndex", "segmentCount", "utf8ByteCount", "content");
        assertThat(schema(properties(segment), "nextSegmentFollowUp"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpResponse"));
    }

    @Test
    void should_describe_closed_revision_bound_event_listener_discovery_contract() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertThat(map(paths.get("/v1/discovery/event-listeners"))).containsOnlyKeys("post");
        Map<String, Object> operation = operation(paths, "/v1/discovery/event-listeners", "post");
        assertThat(operation.get("operationId")).isEqualTo("discoverEventListeners");
        assertRequiredRequestBody(operation, "#/components/schemas/DiscoverEventListenersRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/EventListenerDiscovery"));

        Map<String, Object> request = schema(schemas, "DiscoverEventListenersRequest");
        assertClosedObject(request);
        assertExactProperties(request, "repoId", "eventType", "expectedRevision", "offset", "limit");
        assertThat(required(request)).containsExactlyInAnyOrder("repoId", "eventType", "expectedRevision");
        assertThat(schema(properties(request), "repoId")).containsOnly(
                entry("type", "string"),
                entry("minLength", 1),
                entry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$"));
        assertThat(schema(properties(request), "eventType")).contains(
                entry("maxLength", 1024), entry("pattern", EVENT_TYPE_PATTERN));
        assertThat(schema(properties(request), "expectedRevision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(request), "offset")).contains(
                entry("default", 0), entry("minimum", 0));
        assertThat(schema(properties(request), "limit")).contains(
                entry("default", 50), entry("minimum", 1), entry("maximum", 100));

        Map<String, Object> response = schema(schemas, "DiscoverEventListenersResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "requestedEventType", "candidates", "page", "observationSummaries");
        assertThat(schema(properties(response), "candidates"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("$ref", "#/components/schemas/EventListenerCandidateResponse"));
        assertThat(schema(properties(response), "page"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/CandidatePageResponse"));
        assertThat(schema(properties(response), "observationSummaries"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ListenerObservationSummaryResponse"));

        Map<String, Object> candidate = schema(schemas, "EventListenerCandidateResponse");
        assertClosedObject(candidate);
        assertExactPropertiesAndRequired(candidate, "target", "listenerAnnotations", "sourceRange");
        assertThat(schema(properties(candidate), "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        assertThat(schema(properties(candidate), "listenerAnnotations"))
                .containsEntry("minItems", 1)
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ListenerAnnotationEvidenceResponse"));
        assertThat(schema(properties(candidate), "sourceRange"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceRange"));

        Map<String, Object> page = schema(schemas, "CandidatePageResponse");
        assertClosedObject(page);
        assertExactPropertiesAndRequired(page, "offset", "limit", "returnedCount", "totalCount", "hasMore");

        Map<String, Object> annotation = schema(schemas, "ListenerAnnotationEvidenceResponse");
        assertClosedObject(annotation);
        assertExactPropertiesAndRequired(annotation, "kind", "matchKind");
        assertThat(schema(properties(annotation), "kind"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ListenerAnnotationKind"));
        assertThat(schema(properties(annotation), "matchKind"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/AnnotationMatchKind"));
        assertThat(list(schema(schemas, "ListenerAnnotationKind").get("enum")))
                .containsExactly("EVENT_LISTENER", "TRANSACTIONAL_EVENT_LISTENER");
        assertThat(list(schema(schemas, "AnnotationMatchKind").get("enum")))
                .containsExactly("RESOLVED_IDENTITY", "WRITTEN_NAME");

        Map<String, Object> observation = schema(schemas, "ListenerObservationSummaryResponse");
        assertClosedObject(observation);
        assertExactPropertiesAndRequired(observation, "code", "totalCount", "samples");
        assertThat(schema(properties(observation), "code"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ListenerObservationCode"));
        assertThat(list(schema(schemas, "ListenerObservationCode").get("enum")))
                .containsExactly("LISTENER_TARGET_UNRESOLVED");
        assertThat(schema(properties(observation), "samples"))
                .contains(entry("maxItems", 5), entry("items", Map.of("$ref", "#/components/schemas/SourceRange")));
    }

    @Test
    void should_describe_closed_revision_bound_method_implementation_discovery_contract() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertThat(map(paths.get("/v1/discovery/method-implementations"))).containsOnlyKeys("post");
        Map<String, Object> operation = operation(paths, "/v1/discovery/method-implementations", "post");
        assertThat(operation.get("operationId")).isEqualTo("discoverMethodImplementations");
        assertRequiredRequestBody(operation, "#/components/schemas/DiscoverMethodImplementationsRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "422", "500", "502", "503", "504");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/MethodImplementationDiscovery"));
        assertThat(map(map(operation.get("responses")).get("422")))
                .isEqualTo(Map.of("$ref", "#/components/responses/UnprocessableEntity"));

        Map<String, Object> request = schema(schemas, "DiscoverMethodImplementationsRequest");
        assertClosedObject(request);
        assertExactPropertiesAndRequired(request, "repoId", "expectedRevision", "declarationTarget");
        assertThat(schema(properties(request), "repoId")).containsOnly(
                entry("type", "string"),
                entry("minLength", 1),
                entry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$"));
        assertThat(schema(properties(request), "expectedRevision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(request), "declarationTarget"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        assertThat(properties(request)).doesNotContainKeys("offset", "limit", "page", "cursor");

        Map<String, Object> response = schema(schemas, "DiscoverMethodImplementationsResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "revision", "requestedTarget", "candidates", "limits", "resolution");
        assertThat(schema(properties(response), "revision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(response), "requestedTarget"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        assertThat(schema(schema(properties(response), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodImplementationCandidateResponse"));
        assertThat(schema(properties(response), "limits"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodImplementationLimitsResponse"));
        assertThat(schema(properties(response), "resolution"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodImplementationResolutionResponse"));

        Map<String, Object> candidate = schema(schemas, "MethodImplementationCandidateResponse");
        assertClosedObject(candidate);
        assertExactPropertiesAndRequired(candidate, "target", "primary", "qualifiers", "profiles");
        assertThat(schema(properties(candidate), "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        assertThat(schema(schema(properties(candidate), "qualifiers"), "items"))
                .isEqualTo(Map.of("type", "string"));
        assertThat(schema(schema(properties(candidate), "profiles"), "items"))
                .isEqualTo(Map.of("type", "string"));

        Map<String, Object> limits = schema(schemas, "MethodImplementationLimitsResponse");
        assertClosedObject(limits);
        assertExactPropertiesAndRequired(limits, "candidateLimit", "returnedCount", "totalCount", "truncated");

        Map<String, Object> resolution = schema(schemas, "MethodImplementationResolutionResponse");
        assertClosedObject(resolution);
        assertExactPropertiesAndRequired(resolution, "status", "issueSummaries");
        assertThat(list(schema(properties(resolution), "status").get("enum")))
                .containsExactly("COMPLETE", "PARTIAL");
        assertThat(schema(schema(properties(resolution), "issueSummaries"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SemanticImplementationIssueSummaryResponse"));

        Map<String, Object> summary = schema(schemas, "SemanticImplementationIssueSummaryResponse");
        assertClosedObject(summary);
        assertExactPropertiesAndRequired(summary, "code", "count");
        assertThat(list(schema(properties(summary), "code").get("enum"))).containsExactly(
                "LOCAL_CONVERSION_FAILED",
                "EXTERNAL_TARGET",
                "CANONICAL_TARGET_UNRESOLVED",
                "NON_EXECUTABLE_TARGET");

        Map<String, Object> error = schema(schemas, "ApiErrorResponse");
        assertThat(list(schema(properties(error), "errorCode").get("enum")))
                .contains("IMPLEMENTATION_TARGET_UNSUPPORTED");
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
                .containsExactly("EXPANDED", "DEPTH_BOUNDARY", "BUDGET_CUTOFF", "OPAQUE", "EXTERNAL");

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
                "nodeId", "target", "externalSymbol", "contentState", "traversalState", "dispatchKind", "methodBody",
                "declarationRange");
        assertThat(required(node)).containsExactlyInAnyOrder(
                "nodeId", "contentState", "traversalState", "dispatchKind");
        assertNullableReference(properties(node), "target", "MethodTarget");
        assertNullableString(properties(node), "externalSymbol");
        assertNullableString(properties(node), "methodBody");
        assertNullableReference(properties(node), "declarationRange", "SourceRange");
        assertThat(schema(properties(node), "contentState"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/GraphContentState"));
        assertThat(schema(properties(node), "traversalState"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/GraphTraversalState"));
        assertThat(schema(properties(node), "dispatchKind"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DispatchKind"));
        assertThat(list(schema(schemas, "DispatchKind").get("enum"))).containsExactly("SYNCHRONOUS", "ASYNC");

        Map<String, Object> edge = schema(schemas, "GraphEdge");
        assertClosedObject(edge);
        assertExactPropertiesAndRequired(edge,
                "callerNodeId", "calleeNodeId", "callSite", "callExpression", "resolutionStrategy", "category",
                "evidence");
        assertThat(edge.get("description")).asString().contains("caller", "callee");
        assertRequiredNonNullableArray(edge, "evidence");
        assertThat(schema(properties(edge), "evidence").get("description"))
                .asString().contains("MYBATIS_MAPPER", "SQL");
        assertThat(list(schema(properties(edge), "resolutionStrategy").get("enum"))).containsExactly(
                "JDT_CALL_HIERARCHY",
                "JDT_DEFINITION_FALLBACK",
                "SPRING_BEAN_BY_QUALIFIER",
                "SPRING_BEAN_BY_PRIMARY",
                "SPRING_SINGLE_IMPLEMENTATION",
                "MYBATIS_MAPPER",
                "SPRING_DATA_REPOSITORY",
                "LOMBOK_GENERATED",
                "EXTERNAL_LIBRARY",
                "FEIGN_CLIENT",
                "BUSINESS_READ_FORBIDDEN",
                "SPRING_MULTIPLE_CANDIDATES",
                "DATA_ACCESS_WITHOUT_EVIDENCE");
        assertThat(schema(properties(edge), "resolutionStrategy").get("description"))
                .asString().contains("MYBATIS_MAPPER", "SQL");
        assertThat(list(schema(properties(edge), "category").get("enum"))).containsExactly(
                "RESOLVED_ANALYZABLE", "RESOLVED_OPAQUE", "UNRESOLVED_GUESS");
        assertThat(schema(properties(edge), "category").get("description"))
                .asString().contains("expandable", "terminal", "guess");

        Map<String, Object> warning = schema(schemas, "GraphWarning");
        assertClosedObject(warning);
        assertExactProperties(warning,
                "code", "message", "nodeId", "callExpression", "callSite", "candidates");
        assertThat(required(warning)).containsExactlyInAnyOrder("code", "message", "nodeId", "candidates");
        assertThat(list(schema(properties(warning), "code").get("enum"))).containsExactly(
                "DESCENDANT_CALL_AMBIGUOUS",
                "DESCENDANT_CALL_UNRESOLVED",
                "INCOMING_CALLER_REJECTED",
                "NODE_BUDGET_REACHED");
        assertThat(schema(properties(warning), "code").get("description")).asString()
                .contains("DESCENDANT_CALL_AMBIGUOUS", "NODE_BUDGET_REACHED");
        assertNullableString(properties(warning), "callExpression");
        assertNullableReference(properties(warning), "callSite", "SourceRange");
        assertRequiredNonNullableArray(warning, "candidates");
        assertThat(schema(schema(properties(warning), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));

        Map<String, Object> error = schema(schemas, "GraphError");
        assertClosedObject(error);
        assertExactPropertiesAndRequired(error, "code", "message", "nodeId");
        assertThat(list(schema(properties(error), "code").get("enum")))
                .containsExactly("CHILD_SEMANTIC_QUERY_FAILED");
        assertThat(schema(properties(error), "code").get("description"))
                .asString().contains("CHILD_SEMANTIC_QUERY_FAILED");

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

        assertThat(schemas.keySet()).noneMatch(name -> name.matches(".*(Flattened|Recursive|Cursor|Session|AnalysisStatus).*"));
        assertThat(schemas.keySet().stream().filter(name -> name.contains("Page")).toList())
                .containsExactly("ConceptPageResponse", "CandidatePageResponse");
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
                "EXACT_CONTENT_NOT_FOUND",
                "TYPE_MEMBER_TYPE_NOT_FOUND",
                "SEMANTIC_BINDING_UNRESOLVED",
                "IMPLEMENTATION_TARGET_UNSUPPORTED",
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
                .hasCauseInstanceOf(NullPointerException.class);

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
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_reject_invalid_source_coordinates_and_ranges() {
        assertThatThrownBy(() -> new PositionResponse(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionResponse(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        PositionResponse position = new PositionResponse(0, 0);
        assertThatThrownBy(() -> new SourceRangeResponse(null, position, position))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRangeResponse("src/Main.java", null, position))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceRangeResponse("src/Main.java", position, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_accept_only_non_null_outgoing_graph_limit_reasons() {
        assertThat(new GraphTraversalResponse(2, 1, 10, true, "NONE").limitReason()).isEqualTo("NONE");
        assertThat(new GraphTraversalResponse(2, 1, 10, true, "NODE_BUDGET").limitReason())
                .isEqualTo("NODE_BUDGET");
        assertThatThrownBy(() -> new GraphTraversalResponse(2, 1, 10, true, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphTraversalResponse(2, 1, 10, true, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
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
                .isInstanceOf(NullPointerException.class);
        SourceRangeResponse range = new SourceRangeResponse(
                "src/main/java/com/example/OrderController.java",
                new PositionResponse(0, 0),
                new PositionResponse(0, 1));
        List<String> evidence = new ArrayList<>();
        GraphEdgeResponse edge = new GraphEdgeResponse(
                "caller", "callee", range, "call()", "JDT", "RESOLVED_ANALYZABLE", evidence);
        evidence.add("mutated");
        assertThat(edge.evidence()).isEmpty();
        assertThatThrownBy(() -> new GraphEdgeResponse(
                "caller", "callee", range, "call()", "JDT", "RESOLVED_ANALYZABLE", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphEdgeResponse(
                "caller", "callee", range, "call()", "JDT", null, evidence))
                .isInstanceOf(NullPointerException.class);

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
                .isInstanceOf(NullPointerException.class);

        GraphNodeResponse node = new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null, null);
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
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                null,
                List.of(),
                List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                null,
                List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "FIXTURE",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                List.of(),
                null))
                .isInstanceOf(NullPointerException.class);

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

        assertThat(new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null, null).target()).isNull();
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
        assertThat(map(paths.get("/v1/discovery/event-listeners"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/discovery/method-implementations"))).containsOnlyKeys("post");

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
        assertResponseCodes(operation(paths, "/v1/discovery/event-listeners", "post"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/discovery/method-implementations", "post"),
                "200", "400", "401", "403", "404", "409", "422", "500", "502", "503", "504");
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
        assertRequiredRequestBody(
                operation(paths, "/v1/discovery/event-listeners", "post"),
                "#/components/schemas/DiscoverEventListenersRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/discovery/method-implementations", "post"),
                "#/components/schemas/DiscoverMethodImplementationsRequest");

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
                "packageName", "className", "methodName", "analysisTarget", "matchReasons");
        assertThat(schema(properties(candidate), "analyzedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(candidate), "analysisTarget"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetResolutionResponse"));
        assertThat(list(schema(schema(properties(candidate), "matchReasons"), "items").get("enum")))
                .containsExactly(
                        "EXACT_NORMALIZED_PATH",
                        "TEMPLATE_MATCH",
                        "HTTP_METHOD_MATCH",
                        "SHARED_STATIC_SEGMENT",
                        "POSITIONAL_STATIC_SEGMENT",
                        "SAME_SEGMENT_COUNT");

        Map<String, Object> targetResolution = schema(schemas, "MethodTargetResolutionResponse");
        assertClosedObject(targetResolution);
        assertExactProperties(targetResolution, "status", "target", "candidates", "reasonCode");
        assertThat(required(targetResolution)).containsExactlyInAnyOrder("status", "candidates", "reasonCode");
        assertThat(list(schema(properties(targetResolution), "status").get("enum")))
                .containsExactly("RESOLVED", "UNRESOLVED", "AMBIGUOUS");
        assertNullableReference(properties(targetResolution), "target", "MethodTarget");
        assertRequiredNonNullableArray(targetResolution, "candidates");
        assertThat(schema(schema(properties(targetResolution), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        assertThat(schema(properties(targetResolution), "reasonCode"))
                .isEqualTo(Map.of("type", "string"));

        Map<String, Object> routeResponse = schema(schemas, "ApiRouteCandidatesResponse");
        assertExactPropertiesAndRequired(routeResponse, "candidates", "observations");
        assertThat(schema(schema(properties(routeResponse), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ApiRouteCandidateResponse"));
        assertThat(schema(schema(properties(routeResponse), "observations"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ApiRouteObservationResponse"));

        Map<String, Object> observation = schema(schemas, "ApiRouteObservationResponse");
        assertExactPropertiesAndRequired(observation, "code", "description");
        assertThat(list(schema(properties(observation), "code").get("enum")))
                .containsExactly("TRUNCATED_CANDIDATES");

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
        assertEntryPointMethodAnalysisTarget(
                schemas,
                "ApiEntryPointMethodResponse",
                "name", "description", "type", "apiUrl", "httpMethods", "swaggerDescriptions", "analysisTarget");
        assertEntryPointMethodAnalysisTarget(
                schemas,
                "MqEntryPointMethodResponse",
                "name", "description", "type", "broker", "destinations", "analysisTarget");
        assertEntryPointMethodAnalysisTarget(
                schemas,
                "ScheduleEntryPointMethodResponse",
                "name", "description", "type", "triggerKind", "triggerValue", "analysisTarget");

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

    private void assertPageCoverageAndFollowUpSchemas(Map<String, Object> schemas) {
        Map<String, Object> page = schema(schemas, "ConceptPageResponse");
        assertClosedObject(page);
        assertExactPropertiesAndRequired(page, "offset", "limit", "returnedCount", "totalCount", "hasMore");
        assertThat(schema(properties(page), "offset")).containsEntry("minimum", 0);
        assertThat(schema(properties(page), "limit")).contains(entry("minimum", 1), entry("maximum", 100));
        assertThat(schema(properties(page), "returnedCount")).containsEntry("minimum", 0);
        assertThat(schema(properties(page), "totalCount")).containsEntry("minimum", 0);

        Map<String, Object> coverage = schema(schemas, "ConceptCoverageResponse");
        assertClosedObject(coverage);
        assertExactPropertiesAndRequired(coverage,
                "status", "scannedFileCount", "extractedFileCount", "syntaxFailedFileCount");
        assertThat(list(schema(properties(coverage), "status").get("enum")))
                .containsExactly("COMPLETE", "PARTIAL");

        Map<String, Object> issue = schema(schemas, "ConceptIssueSummaryResponse");
        assertClosedObject(issue);
        assertExactPropertiesAndRequired(issue, "reason", "count");
        assertThat(list(schema(properties(issue), "reason").get("enum")))
                .containsExactly("MQ_DESTINATION_UNRESOLVED", "SCHEDULE_TRIGGER_VALUE_UNRESOLVED");

        Map<String, Object> unavailable = schema(schemas, "UnavailableDiscoveryFollowUpResponse");
        assertClosedObject(unavailable);
        assertExactPropertiesAndRequired(unavailable, "reason", "recommendedAction");
        assertThat(list(schema(properties(unavailable), "reason").get("enum")))
                .containsExactly("NO_MATCHING_STRUCTURED_CONCEPT", "SEARCH_INCOMPLETE");
        assertThat(list(schema(properties(unavailable), "recommendedAction").get("enum"))).containsExactly(
                "REFINE_TERMS_KINDS_OR_PACKAGE_FILTERS", "FIX_SOURCE_OR_RETRY");

        Map<String, Object> candidateFollowUp = schema(schemas, "ConceptCandidateFollowUpResponse");
        assertClosedObject(candidateFollowUp);
        assertExactPropertiesAndRequired(candidateFollowUp, "operation", "api", "request");
        assertThat(list(schema(properties(candidateFollowUp), "operation").get("enum"))).containsExactly(
                "GET_METHOD_SOURCE", "GET_METHOD_SQL", "ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH",
                "DISCOVER_METHOD_IMPLEMENTATIONS", "DISCOVER_CONCEPTS", "GET_TYPE_MEMBERS", "GET_NEXT_PAGE");
        assertThat(schema(properties(candidateFollowUp), "request"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateFollowUpRequestResponse"));

        Map<String, Object> followUp = schema(schemas, "DiscoveryFollowUpResponse");
        assertClosedObject(followUp);
        assertExactPropertiesAndRequired(followUp, "operation", "api", "request");
        assertThat(list(schema(properties(followUp), "operation").get("enum"))).containsExactly(
                "GET_METHOD_SOURCE",
                "GET_METHOD_SQL",
                "GET_MAPPER_FRAGMENT",
                "GET_METHOD_SOURCE_SEGMENT",
                "GET_METHOD_SQL_SEGMENT",
                "GET_MAPPER_FRAGMENT_SEGMENT",
                "ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH",
                "DISCOVER_METHOD_IMPLEMENTATIONS", "DISCOVER_CONCEPTS", "GET_TYPE_MEMBERS", "GET_NEXT_PAGE");
        assertThat(schema(properties(followUp), "api"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpApiResponse"));
        assertThat(schema(properties(followUp), "request"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpRequestResponse"));

        Map<String, Object> api = schema(schemas, "DiscoveryFollowUpApiResponse");
        assertClosedObject(api);
        assertExactPropertiesAndRequired(api, "method", "path", "operationId");
        assertThat(list(schema(properties(api), "method").get("enum"))).containsExactly("POST");

        assertClosedFollowUpRequest(
                schemas, "MethodTargetFollowUpRequestResponse", "repoId", "expectedRevision", "target");
        assertThat(schemas)
                .doesNotContainKeys("GetMethodSourceRequestResponse", "GetMapperStatementRequestResponse");
        Map<String, String> sharedMethodRequest =
                Map.of("$ref", "#/components/schemas/MethodTargetFollowUpRequestResponse");
        assertThat(list(schema(schemas, "ConceptCandidateFollowUpRequestResponse").get("oneOf")))
                .containsOnlyOnce(sharedMethodRequest);
        assertThat(list(schema(schemas, "DiscoveryFollowUpRequestResponse").get("oneOf")))
                .containsOnlyOnce(sharedMethodRequest);
        assertClosedFollowUpRequest(schemas, "GetMapperFragmentRequestResponse",
                "repoId", "expectedRevision", "fragmentIdentity");
        assertThat(required(schema(schemas, "GetMapperFragmentRequestResponse")))
                .containsExactlyInAnyOrder("repoId", "expectedRevision", "fragmentIdentity");
        assertClosedFollowUpRequest(schemas, "GetMethodSourceSegmentRequestResponse",
                "repoId", "expectedRevision", "target", "contentRef", "segmentIndex");
        assertClosedFollowUpRequest(schemas, "GetMapperStatementSegmentRequestResponse",
                "repoId", "expectedRevision", "target", "contentRef", "segmentIndex");
        assertClosedFollowUpRequest(schemas, "GetMapperFragmentSegmentRequestResponse",
                "repoId", "expectedRevision", "fragmentIdentity", "contentRef", "segmentIndex");
        assertClosedFollowUpRequest(schemas, "AnalyzeCallGraphRequestResponse", "repoId", "expectedRevision", "depth", "target");
        assertClosedFollowUpRequest(schemas, "DiscoverMethodImplementationsRequestResponse",
                "repoId", "expectedRevision", "declarationTarget");
        assertClosedFollowUpRequest(schemas, "DiscoverConceptsRequestResponse",
                "operator", "repoId", "expectedRevision", "terms", "kinds", "packagePrefix", "offset", "limit");
        assertThat(schema(properties(schema(schemas, "DiscoverConceptsRequestResponse")), "kinds"))
                .contains(entry("minItems", 1), entry("uniqueItems", Boolean.TRUE))
                .doesNotContainKey("maxItems");
        assertClosedFollowUpRequest(schemas, "GetTypeMembersRequestResponse",
                "repoId", "expectedRevision", "sourceFile", "fullyQualifiedName", "memberKinds", "namePrefix", "offset", "limit");
        assertClosedFollowUpRequest(schemas, "DiscoverTypeMembersRequestResponse",
                "repoId", "expectedRevision", "sourceFile", "fullyQualifiedName", "memberKinds", "namePrefix", "offset", "limit");
        assertThat(properties(schema(schemas, "GetTypeMembersRequestResponse")))
                .containsKey("fullyQualifiedName")
                .doesNotContainKey("fullyQualifiedTypeName");
        assertThat(list(schema(schemas, "DiscoveryFollowUpRequestResponse").get("oneOf")))
                .contains(Map.of("$ref", "#/components/schemas/GetMapperFragmentRequestResponse"));
    }

    private void assertMapperMappingStateSchema(
            Map<String, Object> schemas,
            String schemaName,
            String status,
            String candidateSchema,
            int minimumCandidates,
            Optional<Integer> maximumCandidates,
            boolean unresolved) {
        Map<String, Object> mapping = schema(schemas, schemaName);
        assertClosedObject(mapping);
        if (unresolved) {
            assertExactPropertiesAndRequired(mapping, "namespace", "statementId", "status", "reason", "candidates");
            assertThat(list(schema(properties(mapping), "reason").get("enum")))
                    .containsExactly("INCOMPLETE_METHOD_RESOLUTION");
        } else {
            assertExactPropertiesAndRequired(mapping, "namespace", "statementId", "status", "candidates");
        }
        assertThat(list(schema(properties(mapping), "status").get("enum"))).containsExactly(status);
        Map<String, Object> candidates = schema(properties(mapping), "candidates");
        assertThat(candidates).containsEntry("uniqueItems", Boolean.TRUE);
        if (minimumCandidates > 0) {
            assertThat(candidates).containsEntry("minItems", minimumCandidates);
        } else {
            assertThat(candidates).doesNotContainKey("minItems");
        }
        maximumCandidates.ifPresent(maximum -> assertThat(candidates).containsEntry("maxItems", maximum));
        assertThat(schema(candidates, "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/" + candidateSchema));
    }

    private void assertMapperMethodCandidateSchema(
            Map<String, Object> schemas,
            String schemaName,
            String operation,
            String path,
            String operationId) {
        Map<String, Object> candidate = schema(schemas, schemaName);
        assertClosedObject(candidate);
        assertExactPropertiesAndRequired(candidate, "target", "availableFollowUps");
        assertThat(schema(properties(candidate), "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTarget"));
        Map<String, Object> followUps = schema(properties(candidate), "availableFollowUps");
        assertThat(followUps).contains(entry("minItems", 1), entry("maxItems", 1));
        List<Object> followUpConstraints = list(schema(followUps, "items").get("allOf"));
        assertThat(followUpConstraints.getFirst())
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateFollowUpResponse"));
        Map<String, Object> operationConstraint = map(followUpConstraints.get(1));
        assertThat(list(schema(properties(operationConstraint), "operation").get("enum")))
                .containsExactly(operation);
        assertThat(schema(properties(operationConstraint), "request"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetFollowUpRequestResponse"));
        List<Object> apiConstraints = list(schema(properties(operationConstraint), "api").get("allOf"));
        assertThat(apiConstraints.getFirst())
                .isEqualTo(Map.of("$ref", "#/components/schemas/DiscoveryFollowUpApiResponse"));
        Map<String, Object> apiValueConstraints = map(apiConstraints.get(1));
        assertThat(list(schema(properties(apiValueConstraints), "path").get("enum"))).containsExactly(path);
        assertThat(list(schema(properties(apiValueConstraints), "operationId").get("enum")))
                .containsExactly(operationId);
    }

    private void assertOpenApiPayloadValid(String schemaName, Map<String, Object> payload) throws Exception {
        assertThat(openApiJsonSchema(schemaName).validInstance(objectMapper.valueToTree(payload)))
                .as("payload should match %s: %s", schemaName, payload)
                .isTrue();
    }

    private void assertOpenApiPayloadInvalid(String schemaName, Map<String, Object> payload) throws Exception {
        assertThat(openApiJsonSchema(schemaName).validInstance(objectMapper.valueToTree(payload)))
                .as("payload should not match %s: %s", schemaName, payload)
                .isFalse();
    }

    private JsonSchema openApiJsonSchema(String schemaName) throws Exception {
        ObjectNode rootSchema = objectMapper.createObjectNode();
        rootSchema.put("$schema", "http://json-schema.org/draft-04/schema#");
        rootSchema.put("$ref", "#/components/schemas/" + schemaName);
        JsonNode components = objectMapper.valueToTree(Map.of("schemas", schemas()));
        rootSchema.set("components", components);
        return JsonSchemaFactory.byDefault().getJsonSchema(rootSchema);
    }

    private static Map<String, Object> methodFollowUpRequest(String sourceFile) {
        return Map.of(
                "repoId", "orders",
                "expectedRevision", "1111111111111111111111111111111111111111",
                "target", methodTargetPayload(sourceFile, "java.lang.String"));
    }

    private static Map<String, Object> methodTargetPayload(String sourceFile, String parameterType) {
        return Map.of(
                "sourceFile", sourceFile,
                "packageName", "com.example",
                "className", "p",
                "methodName", "p",
                "parameterTypes", List.of(parameterType));
    }

    private static Map<String, Object> mapperFollowUp(
            String operation,
            String path,
            String operationId,
            Map<String, Object> target) {
        return Map.of(
                "operation", operation,
                "api", Map.of("method", "POST", "path", path, "operationId", operationId),
                "request", Map.of(
                        "repoId", "orders",
                        "expectedRevision", "1111111111111111111111111111111111111111",
                        "target", target));
    }

    private static Map<String, Object> mapperCandidate(
            Map<String, Object> target,
            Map<String, Object> followUp) {
        return Map.of("target", target, "availableFollowUps", List.of(followUp));
    }

    private static Map<String, Object> mapperMapping(String status, List<Map<String, Object>> candidates) {
        return Map.of(
                "namespace", "com.example.OrderMapper",
                "statementId", "findOrders",
                "status", status,
                "candidates", candidates);
    }

    private static Map<String, Object> unresolvedMapperMapping(List<Map<String, Object>> candidates) {
        return Map.of(
                "namespace", "com.example.OrderMapper",
                "statementId", "findOrders",
                "status", "UNRESOLVED",
                "reason", "INCOMPLETE_METHOD_RESOLUTION",
                "candidates", candidates);
    }

    private void assertClosedFollowUpRequest(Map<String, Object> schemas, String name, String... properties) {
        Map<String, Object> request = schema(schemas, name);
        assertClosedObject(request);
        assertExactProperties(request, properties);
    }

    private void assertExactContentOperation(
            Map<String, Object> paths,
            String path,
            String operationId,
            String requestSchema,
            String responseName) {
        assertThat(map(paths.get(path))).containsOnlyKeys("post");
        Map<String, Object> operation = operation(paths, path, "post");
        assertThat(operation.get("operationId")).isEqualTo(operationId);
        assertRequiredRequestBody(operation, "#/components/schemas/" + requestSchema);
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/" + responseName));
    }

    private void assertClosedExactRequest(
            Map<String, Object> schemas,
            String schemaName,
            String authorityName) {
        Map<String, Object> request = schema(schemas, schemaName);
        assertClosedObject(request);
        assertExactPropertiesAndRequired(request, "repoId", "expectedRevision", authorityName);
        assertThat(schema(properties(request), "expectedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
    }

    private void assertClosedExactSegmentRequest(
            Map<String, Object> schemas,
            String schemaName,
            String authorityName) {
        Map<String, Object> request = schema(schemas, schemaName);
        assertClosedObject(request);
        assertExactPropertiesAndRequired(
                request, "repoId", "expectedRevision", authorityName, "contentRef", "segmentIndex");
        assertThat(schema(properties(request), "contentRef"))
                .contains(entry("minLength", 1), entry("maxLength", 128));
        assertThat(schema(properties(request), "segmentIndex")).containsEntry("minimum", 0);
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

    private void assertEntryPointMethodAnalysisTarget(
            Map<String, Object> schemas, String schemaName, String... propertyNames) {
        Map<String, Object> response = schema(schemas, schemaName);
        assertExactPropertiesAndRequired(response, propertyNames);
        assertThat(schema(properties(response), "analysisTarget"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetResolutionResponse"));
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
