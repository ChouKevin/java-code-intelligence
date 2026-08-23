package com.java.semantic.api;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.dto.GraphEdgeResponse;
import com.java.semantic.api.dto.GraphErrorResponse;
import com.java.semantic.api.dto.GraphNodeResponse;
import com.java.semantic.api.dto.GraphTraversalResponse;
import com.java.semantic.api.dto.GraphWarningResponse;
import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.syntax.application.concept.ConceptKind;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class OpenApiContractTest {

    private static final String REVISION_PATTERN = "^[0-9a-f]{40}$";
    private static final String SOURCE_FILE_PATTERN = "^[^\\u0000-\\u001F\\u007F-\\u009F]*[^\\u0000-\\u0020\\u007F-\\u009F\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028-\\u2029\\u205F\\u3000][^\\u0000-\\u001F\\u007F-\\u009F]*$";
    private static final String CLASS_NAME_PATTERN = "^[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*(?:\\.[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*)*$";
    private static final String METHOD_NAME_PATTERN = "^[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}][\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Mn}\\p{Mc}\\p{Nd}]*$";
    private static final String EVENT_TYPE_PATTERN = "^(?:[\\p{L}_][\\p{L}\\p{N}_]*\\.)+[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[\\])*$";
    private static final TypeReference<Map<String, Object>> DOCUMENT_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Map<String, Object> document;
    private JsonNode documentTree;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                getClass().getResourceAsStream("/openapi/semantic-api-v1.yaml"),
                "OpenAPI document is required")) {
            documentTree = YAML_MAPPER.readTree(input);
            document = YAML_MAPPER.convertValue(documentTree, DOCUMENT_TYPE);
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
                "/v1/discovery/source-symbols/resolve",
                "/v1/discovery/internal-references",
                "/v1/discovery/source-segment",
                "/v1/discovery/concepts",
                "/v1/discovery/concepts/resolve",
                "/v1/discovery/type-members",
                "/v1/discovery/method-source",
                "/v1/discovery/evidence-source"));
        assertThat(paths.keySet()).doesNotContain(
                "/v1/analyses/call-graph",
                "/v1/analyses/call-graph/flatten",
                "/v1/analyses/call-graphs/expand",
                "/v1/discovery/java-source-segment");
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
    void should_describe_closed_internal_reference_and_bounded_source_segment_contracts() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> references = operation(paths, "/v1/discovery/internal-references", "post");
        Map<String, Object> segment = operation(paths, "/v1/discovery/source-segment", "post");
        assertThat(references.get("operationId")).isEqualTo("findInternalReferences");
        assertThat(segment.get("operationId")).isEqualTo("getSourceSegment");
        assertRequiredRequestBody(references, "#/components/schemas/InternalSourceReferenceRequest");
        assertRequiredRequestBody(segment, "#/components/schemas/SourceSegmentRequest");
        assertResponseCodes(references, "200", "400", "401", "403", "404", "409", "502", "503", "504");
        assertResponseCodes(segment, "200", "400", "401", "403", "404", "409");

        Map<String, Object> schemas = schemas();
        Map<String, Object> request = schema(schemas, "InternalSourceReferenceRequest");
        assertClosedObject(request);
        assertExactProperties(request, "repoId", "expectedRevision", "target", "offset", "limit");
        assertThat(required(request)).containsExactlyInAnyOrder("repoId", "expectedRevision", "target");
        assertThat(schema(properties(request), "offset")).containsEntry("default", 0);
        assertThat(schema(properties(request), "limit")).contains(entry("default", 20), entry("maximum", 100));
        assertThat(list(schema(schemas, "InternalSourceReferenceTargetPayload").get("oneOf"))).containsExactly(
                Map.of("$ref", "#/components/schemas/InternalSourceReferenceTypeTargetPayload"),
                Map.of("$ref", "#/components/schemas/InternalSourceReferenceMethodTargetPayload"),
                Map.of("$ref", "#/components/schemas/InternalSourceReferenceMemberTargetPayload"));
        assertClosedFollowUpRequest(schemas, "InternalSourceReferenceTypeTargetPayload", "kind", "identity");
        assertClosedFollowUpRequest(schemas, "InternalSourceReferenceMethodTargetPayload", "kind", "identity");
        assertClosedFollowUpRequest(schemas, "InternalSourceReferenceMemberTargetPayload", "kind", "identity");

        Map<String, Object> response = schema(schemas, "InternalSourceReferenceResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "status", "targetDeclaration", "totalReferenceCount",
                "referenceGroups", "page", "issueSummaries", "availableFollowUps");
        Map<String, Object> group = schema(schemas, "InternalSourceReferenceGroupResponse");
        assertClosedObject(group);
        assertThat(schema(properties(group), "representativeReferences")).containsEntry("maxItems", 3);
        assertThat(schema(properties(group), "limits"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/BoundedResultResponse"));
        assertExactPropertiesAndRequired(schema(schemas, "InternalSourceReferenceIssueSummaryResponse"),
                "code", "count");

        Map<String, Object> segmentRequest = schema(schemas, "SourceSegmentRequest");
        assertClosedObject(segmentRequest);
        assertExactProperties(segmentRequest, "repoId", "expectedRevision", "location", "contextLines");
        assertThat(required(segmentRequest)).containsExactlyInAnyOrder("repoId", "expectedRevision", "location");
        assertThat(schema(properties(segmentRequest), "contextLines"))
                .contains(
                        entry("minimum", 0),
                        entry("maximum", 20),
                        entry("default", 0),
                        entry("description",
                                "Surrounding context for canonical Java ranges; mapper XML evidence requires 0"));
        Map<String, Object> segmentResponse = schema(schemas, "SourceSegmentResponse");
        assertClosedObject(segmentResponse);
        assertExactPropertiesAndRequired(segmentResponse,
                "repoId", "analyzedRevision", "segment", "contextTruncated", "availableFollowUps");
        assertRequiredNonNullableArray(segmentResponse, "availableFollowUps");
        Map<String, Object> payload = schema(schemas, "SourceSegmentPayload");
        assertClosedObject(payload);
        assertExactProperties(payload, "location", "content", "nextLocation");
        assertThat(required(payload)).containsExactlyInAnyOrder("location", "content");
        assertThat(schemas.keySet()).doesNotContain(
                "JavaSourceSegmentRequest", "JavaSourceSegmentResponse", "JavaSourceSegmentPayload");
        Map<String, Object> methodSource = schema(schemas, "MethodSourceResponse");
        assertClosedObject(methodSource);
        assertExactPropertiesAndRequired(methodSource,
                "repoId", "analyzedRevision", "declarationLocation", "segment", "availableFollowUps");
        assertRequiredNonNullableArray(methodSource, "availableFollowUps");
    }

    @Test
    void should_describe_closed_source_symbol_request_and_polymorphic_response() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();
        Map<String, Object> operation = operation(paths, "/v1/discovery/source-symbols/resolve", "post");

        assertThat(operation.get("operationId")).isEqualTo("resolveSourceSymbol");
        assertRequiredRequestBody(operation, "#/components/schemas/ResolveSourceSymbolRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/SourceSymbolResolution"));

        Map<String, Object> request = schema(schemas, "ResolveSourceSymbolRequest");
        assertClosedObject(request);
        assertExactProperties(request, "repoId", "expectedRevision", "context", "symbol", "position");
        assertThat(required(request)).containsExactlyInAnyOrder("repoId", "expectedRevision", "context", "symbol");
        assertThat(schema(properties(request), "expectedRevision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(request), "position"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/PositionPayload"));
        assertThat(schema(properties(request), "context"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceSymbolContextPayload"));
        Map<String, Object> context = schema(schemas, "SourceSymbolContextPayload");
        assertClosedObject(context);
        assertExactProperties(context, "javaType", "sourceFile", "method");
        assertThat(required(context)).containsExactly("javaType");
        assertThat(schema(properties(context), "javaType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/JavaTypeIdentityPayload"));
        Map<String, Object> methodContext = schema(schemas, "SourceSymbolMethodContextPayload");
        assertClosedObject(methodContext);
        assertExactProperties(methodContext, "name", "parameterTypes");
        assertThat(required(methodContext)).containsExactly("name");

        Map<String, Object> response = schema(schemas, "SourceSymbolResolutionResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "status", "contextCandidates",
                "contextCandidateLimits", "candidates", "issues");

        Map<String, Object> contextCandidate = schema(schemas, "SourceContextCandidateResponse");
        assertThat(list(contextCandidate.get("oneOf"))).containsExactly(
                Map.of("$ref", "#/components/schemas/SourceTypeContextCandidateResponse"),
                Map.of("$ref", "#/components/schemas/SourceMethodContextCandidateResponse"));
        assertThat(map(schema(contextCandidate, "discriminator").get("mapping")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SOURCE_TYPE", "#/components/schemas/SourceTypeContextCandidateResponse",
                        "METHOD", "#/components/schemas/SourceMethodContextCandidateResponse"));
        assertExactPropertiesAndRequired(schema(schemas, "SourceTypeContextCandidateResponse"),
                "kind", "sourceFile", "retry");
        assertExactPropertiesAndRequired(schema(schemas, "SourceMethodContextCandidateResponse"),
                "kind", "target", "retry");

        Map<String, Object> symbolCandidate = schema(schemas, "SourceSymbolCandidateResponse");
        assertThat(list(symbolCandidate.get("oneOf"))).containsExactly(
                Map.of("$ref", "#/components/schemas/VariableLikeSourceSymbolCandidateResponse"),
                Map.of("$ref", "#/components/schemas/StaticConstantSourceSymbolCandidateResponse"),
                Map.of("$ref", "#/components/schemas/MethodSourceSymbolCandidateResponse"),
                Map.of("$ref", "#/components/schemas/SourceTypeSymbolCandidateResponse"));
        assertThat(map(schema(symbolCandidate, "discriminator").get("mapping")))
                .containsEntry("STATIC_CONSTANT", "#/components/schemas/StaticConstantSourceSymbolCandidateResponse")
                .containsEntry("METHOD", "#/components/schemas/MethodSourceSymbolCandidateResponse")
                .containsEntry("SOURCE_TYPE", "#/components/schemas/SourceTypeSymbolCandidateResponse");
        assertThat(properties(schema(schemas, "StaticConstantSourceSymbolCandidateResponse")))
                .containsKey("initializerSource");
        assertThat(required(schema(schemas, "StaticConstantSourceSymbolCandidateResponse")))
                .contains("initializerSource");
        assertThat(properties(schema(schemas, "VariableLikeSourceSymbolCandidateResponse")))
                .containsKey("identity")
                .doesNotContainKeys("initializerSource", "name", "declarationOwner");
        assertThat(properties(schema(schemas, "MethodSourceSymbolCandidateResponse")))
                .doesNotContainKey("name");
        assertExactPropertiesAndRequired(schema(schemas, "SourceTypeSymbolCandidateResponse"),
                "kind", "identity", "declarationRange", "representativeOccurrence",
                "occurrenceCount", "availableFollowUps");
        assertDiscriminatedUnion(
                schemas,
                "SourceMemberIdentityPayload",
                "scope",
                Map.of(
                        "TYPE", "TypeMemberIdentityPayload",
                        "METHOD", "MethodScopedIdentityPayload"));
        assertExactPropertiesAndRequired(
                schema(schemas, "TypeMemberIdentityPayload"), "scope", "ownerType", "name");
        assertExactPropertiesAndRequired(schema(schemas, "MethodScopedIdentityPayload"),
                "scope", "declaringMethod", "declarationRange", "name");

        assertThat(list(schema(schemas, "DiscoveryFollowUpRequestResponse").get("oneOf")))
                .contains(
                        Map.of("$ref", "#/components/schemas/ResolveSourceSymbolRequestResponse"),
                        Map.of("$ref", "#/components/schemas/ResolveConceptRequestResponse"));
    }

    @Test
    void should_describe_exact_typed_concept_resolution_without_search_parameters() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();
        Map<String, Object> operation = operation(paths, "/v1/discovery/concepts/resolve", "post");

        assertThat(operation.get("operationId")).isEqualTo("resolveConcept");
        assertRequiredRequestBody(operation, "#/components/schemas/ResolveConceptRequest");
        assertResponseCodes(operation, "200", "400", "401", "403", "404", "409", "500");
        assertThat(map(map(operation.get("responses")).get("200")))
                .isEqualTo(Map.of("$ref", "#/components/responses/ConceptResolution"));

        Map<String, Object> request = schema(schemas, "ResolveConceptRequest");
        assertClosedObject(request);
        assertExactPropertiesAndRequired(request, "repoId", "expectedRevision", "identity");
        assertThat(schema(properties(request), "expectedRevision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(request), "identity"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptIdentityResponse"));

        Map<String, Object> response = schema(schemas, "ResolveConceptResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response, "repoId", "analyzedRevision", "candidate");
        assertThat(schema(properties(response), "candidate"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateResponse"));
        assertThat(schema(properties(response), "analyzedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);

        Map<String, Object> fieldIdentity = schema(schemas, "FieldConceptIdentityResponse");
        assertExactPropertiesAndRequired(fieldIdentity, "kind", "identity");
        assertThat(schema(properties(fieldIdentity), "identity"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceMemberIdentityPayload"));
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
                .containsExactly("TOKEN_EXACT", "TOKEN_PREFIX");

        Map<String, Object> response = schema(schemas, "DiscoverConceptsResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "normalizedTerms", "searchedKinds", "supportedKinds", "limitations",
                "candidates", "page", "coverage", "issueSummaries", "availableFollowUps", "unavailableFollowUps");
        assertThat(schema(properties(response), "supportedKinds"))
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ConceptKind"));
        assertThat(schema(properties(response), "page"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/PageResponse"));
        assertThat(schema(properties(response), "coverage"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCoverageResponse"));

        Map<String, Object> candidate = schema(schemas, "ConceptCandidateResponse");
        assertClosedObject(candidate);
        assertExactProperties(candidate,
                "identity", "displayValue", "matchedTerms", "authority", "details", "evidence", "availableFollowUps");
        assertThat(required(candidate)).containsExactlyInAnyOrder(
                "identity", "displayValue", "matchedTerms", "authority", "evidence", "availableFollowUps");
        assertThat(schema(properties(candidate), "identity"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptIdentityResponse"));
        assertThat(schema(properties(candidate), "details"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateDetailsResponse"));
        assertThat(schema(schema(properties(candidate), "availableFollowUps"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateFollowUpResponse"));

        assertClosedConceptIdentitySchemas(schemas);
        assertClosedFieldTypeReferenceSchemas(schemas);

        Map<String, Object> evidence = schema(schemas, "ConceptEvidenceResponse");
        assertClosedObject(evidence);
        assertExactPropertiesAndRequired(evidence, "identity");
        assertThat(schema(properties(evidence), "identity"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptIdentityResponse"));

        assertPageCoverageAndFollowUpSchemas(schemas);
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
                "repoId", "expectedRevision", "sourceType", "memberKinds", "namePrefix", "offset", "limit");
        assertThat(required(request)).containsExactlyInAnyOrder(
                "repoId", "expectedRevision", "sourceType", "memberKinds");
        assertThat(schema(properties(request), "sourceType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceTypeIdentityPayload"));
        assertThat(schema(properties(request), "memberKinds")).contains(
                entry("type", "array"), entry("minItems", 1),
                entry("items", Map.of("$ref", "#/components/schemas/TypeMemberKind")));
        assertThat(list(schema(schemas, "TypeMemberKind").get("enum"))).containsExactly("METHOD", "FIELD");

        Map<String, Object> response = schema(schemas, "DiscoverTypeMembersResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "sourceType", "typeKind", "annotations",
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
        Map<String, Object> fieldMember = schema(schemas, "FieldTypeMemberResponse");
        assertExactProperties(fieldMember,
                "kind", "identity", "writtenType", "resolvedType", "annotations", "limitations",
                "availableFollowUps");
        assertThat(required(fieldMember)).containsExactlyInAnyOrder(
                "kind", "identity", "writtenType", "annotations", "limitations", "availableFollowUps");
    }

    @Test
    void should_describe_closed_typed_evidence_source_and_bounded_method_source_contracts() {
        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> schemas = schemas();

        assertSourceOperation(paths, "/v1/discovery/method-source", "getMethodSource",
                "GetMethodSourceRequest", "MethodSource");
        assertSourceOperation(paths, "/v1/discovery/evidence-source", "getEvidenceSource",
                "EvidenceSourceRequest", "EvidenceSource");
        assertThat(paths.keySet()).contains("/v1/discovery/method-source", "/v1/discovery/evidence-source");

        assertClosedSourceRequest(schemas, "GetMethodSourceRequest", "target");
        assertClosedSourceRequest(schemas, "EvidenceSourceRequest", "identity");

        Map<String, Object> identity = schema(schemas, "EvidenceSourceIdentityPayload");
        assertClosedObject(identity);
        assertExactProperties(identity, "kind", "statementIdentity", "fragmentIdentity");
        assertThat(required(identity)).containsExactly("kind");
        assertThat(list(schema(properties(identity), "kind").get("enum")))
                .containsExactly("ANNOTATION_SQL", "MAPPER_STATEMENT", "MAPPER_FRAGMENT");
        assertThat(list(identity.get("oneOf"))).hasSize(2);

        Map<String, Object> response = schema(schemas, "EvidenceSourceResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "analyzedRevision", "identity", "location", "segment", "availableFollowUps");
        assertRequiredNonNullableArray(response, "availableFollowUps");
        assertThat(schema(properties(response), "location"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceRangePayload"));
        assertThat(schema(properties(response), "segment"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceSegmentPayload"));
        assertThat(schemas.keySet()).contains("EvidenceSourceRequest", "EvidenceSourceIdentityPayload");
    }

    @Test
    void should_keep_tracked_uat_on_typed_evidence_and_shared_source_segment_routes() throws IOException {
        String uat = Files.readString(Path.of("uat", "structured-concept-discovery.http"));
        Map<String, Object> paths = map(document.get("paths"));
        List<String> postRoutes = uat.lines()
                .filter(line -> line.startsWith("POST {{baseUrl}}"))
                .map(line -> line.substring("POST {{baseUrl}}".length()))
                .toList();

        assertThat(postRoutes)
                .contains("/v1/discovery/evidence-source", "/v1/discovery/source-segment")
                .allMatch(paths::containsKey);
        assertThat(List.of(
                occurrenceCount(uat, "client.global.set(\"methodSourceSegmentRequest\""),
                occurrenceCount(uat, "client.global.set(\"statementSourceSegmentRequest\""),
                occurrenceCount(uat, "client.global.set(\"fragmentSourceSegmentRequest\"")))
                .containsExactly(2L, 2L, 2L);
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
                "repoId", "analyzedRevision", "requestedEventType", "candidates", "page", "observationSummaries",
                "availableFollowUps");
        assertRequiredNonNullableArray(response, "availableFollowUps");
        assertThat(schema(properties(response), "candidates"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("$ref", "#/components/schemas/EventListenerCandidateResponse"));
        assertThat(schema(properties(response), "page"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/PageResponse"));
        assertThat(schema(properties(response), "observationSummaries"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ListenerObservationSummaryResponse"));

        Map<String, Object> candidate = schema(schemas, "EventListenerCandidateResponse");
        assertClosedObject(candidate);
        assertExactPropertiesAndRequired(candidate, "target", "listenerAnnotations", "sourceRange", "availableFollowUps");
        assertRequiredNonNullableArray(candidate, "availableFollowUps");
        assertThat(schema(properties(candidate), "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));
        assertThat(schema(properties(candidate), "listenerAnnotations"))
                .containsEntry("minItems", 1)
                .containsEntry("items", Map.of("$ref", "#/components/schemas/ListenerAnnotationEvidenceResponse"));
        assertThat(schema(properties(candidate), "sourceRange"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/TextRangePayload"));

        Map<String, Object> page = schema(schemas, "PageResponse");
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
                .contains(entry("maxItems", 5),
                        entry("items", Map.of("$ref", "#/components/schemas/SourceRangePayload")));
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
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));
        assertThat(properties(request)).doesNotContainKeys("offset", "limit", "page", "cursor");

        Map<String, Object> response = schema(schemas, "DiscoverMethodImplementationsResponse");
        assertClosedObject(response);
        assertExactPropertiesAndRequired(response,
                "repoId", "revision", "requestedTarget", "candidates", "limits", "resolution");
        assertThat(schema(properties(response), "revision")).containsEntry("pattern", REVISION_PATTERN);
        assertThat(schema(properties(response), "requestedTarget"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));
        assertThat(schema(schema(properties(response), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodImplementationCandidateResponse"));
        assertThat(schema(properties(response), "limits"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/BoundedResultResponse"));
        assertThat(schema(properties(response), "resolution"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodImplementationResolutionResponse"));

        Map<String, Object> candidate = schema(schemas, "MethodImplementationCandidateResponse");
        assertClosedObject(candidate);
        assertExactPropertiesAndRequired(candidate, "target", "primary", "qualifiers", "profiles", "availableFollowUps");
        assertRequiredNonNullableArray(candidate, "availableFollowUps");
        assertThat(schema(properties(candidate), "target"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));
        assertThat(schema(schema(properties(candidate), "qualifiers"), "items"))
                .isEqualTo(Map.of("type", "string"));
        assertThat(schema(schema(properties(candidate), "profiles"), "items"))
                .isEqualTo(Map.of("type", "string"));

        Map<String, Object> limits = schema(schemas, "BoundedResultResponse");
        assertClosedObject(limits);
        assertExactPropertiesAndRequired(limits, "limit", "returnedCount", "totalCount", "truncated");

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
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));

        Map<String, Object> javaType = schema(schemas, "JavaTypeIdentityPayload");
        assertClosedObject(javaType);
        assertExactPropertiesAndRequired(javaType, "packageName", "className");
        Map<String, Object> sourceType = schema(schemas, "SourceTypeIdentityPayload");
        assertClosedObject(sourceType);
        assertExactPropertiesAndRequired(sourceType, "javaType", "sourceFile");
        assertThat(schema(properties(sourceType), "javaType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/JavaTypeIdentityPayload"));

        Map<String, Object> target = schema(schemas, "MethodTargetPayload");
        assertClosedObject(target);
        assertThat(required(target)).containsExactly("sourceType", "methodName", "parameterTypes");
        Map<String, Object> targetProperties = properties(target);
        assertThat(targetProperties.keySet()).containsExactlyInAnyOrder("sourceType", "methodName", "parameterTypes");
        assertThat(schema(targetProperties, "sourceType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceTypeIdentityPayload"));
        Map<String, Object> sourceFile = schema(properties(sourceType), "sourceFile");
        assertThat(sourceFile)
                .contains(entry("maxLength", 1024), entry("pattern", SOURCE_FILE_PATTERN));
        Pattern sourceFilePattern = Pattern.compile((String) sourceFile.get("pattern"));
        assertThat(sourceFilePattern.matcher("src/main/java/Main.java").matches()).isTrue();
        assertThat(sourceFilePattern.matcher("   ").matches()).isFalse();
        assertThat(sourceFilePattern.matcher("src/Main" + '\0' + ".java").matches()).isFalse();
        assertThat(schema(properties(javaType), "className"))
                .contains(entry("maxLength", 255), entry("pattern", CLASS_NAME_PATTERN));
        assertThat(schema(targetProperties, "methodName"))
                .contains(entry("maxLength", 255), entry("pattern", METHOD_NAME_PATTERN));
        assertThat(schema(properties(javaType), "className").get("description"))
                .asString().contains("Unicode", "server");
        assertThat(schema(properties(javaType), "packageName")).containsOnly(entry("type", "string"));
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
                "nodeId", "target", "externalSymbol", "contentState", "traversalState", "dispatchKind",
                "declarationRange", "availableFollowUps");
        assertThat(required(node)).containsExactlyInAnyOrder(
                "nodeId", "contentState", "traversalState", "dispatchKind", "availableFollowUps");
        assertRequiredNonNullableArray(node, "availableFollowUps");
        assertNullableReference(properties(node), "target", "MethodTargetPayload");
        assertNullableString(properties(node), "externalSymbol");
        assertNullableReference(properties(node), "declarationRange", "TextRangePayload");
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
                "code", "message", "nodeId", "callExpression", "callSite", "candidates", "availableFollowUps");
        assertThat(required(warning)).containsExactlyInAnyOrder(
                "code", "message", "nodeId", "candidates", "availableFollowUps");
        assertRequiredNonNullableArray(warning, "availableFollowUps");
        assertThat(list(schema(properties(warning), "code").get("enum"))).containsExactly(
                "DESCENDANT_CALL_AMBIGUOUS",
                "DESCENDANT_CALL_UNRESOLVED",
                "INCOMING_CALLER_REJECTED",
                "NODE_BUDGET_REACHED");
        assertThat(schema(properties(warning), "code").get("description")).asString()
                .contains("DESCENDANT_CALL_AMBIGUOUS", "NODE_BUDGET_REACHED");
        assertNullableString(properties(warning), "callExpression");
        assertNullableReference(properties(warning), "callSite", "SourceRangePayload");
        assertRequiredNonNullableArray(warning, "candidates");
        assertThat(schema(schema(properties(warning), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));

        Map<String, Object> error = schema(schemas, "GraphError");
        assertClosedObject(error);
        assertExactPropertiesAndRequired(error, "code", "message", "nodeId");
        assertThat(list(schema(properties(error), "code").get("enum")))
                .containsExactly("CHILD_SEMANTIC_QUERY_FAILED");
        assertThat(schema(properties(error), "code").get("description"))
                .asString().contains("CHILD_SEMANTIC_QUERY_FAILED");

        Map<String, Object> position = schema(schemas, "PositionPayload");
        assertClosedObject(position);
        assertThat(position.get("description")).asString().contains("Zero-based UTF-16");
        assertExactPropertiesAndRequired(position, "line", "character");
        Map<String, Object> textRange = schema(schemas, "TextRangePayload");
        assertClosedObject(textRange);
        assertThat(textRange.get("description")).asString().contains("start-inclusive", "end-exclusive");
        assertExactPropertiesAndRequired(textRange, "start", "end");
        Map<String, Object> sourceRange = schema(schemas, "SourceRangePayload");
        assertClosedObject(sourceRange);
        assertExactPropertiesAndRequired(sourceRange, "sourceFile", "range");
        assertThat(schemas).doesNotContainKeys("Position", "SourceRange");

        assertRequiredNonNullableArray(response, "nodes");
        assertRequiredNonNullableArray(response, "edges");
        assertRequiredNonNullableArray(response, "warnings");
        assertRequiredNonNullableArray(response, "errors");

        assertThat(schemas.keySet()).noneMatch(name -> name.matches(".*(Flattened|Recursive|Cursor|Session|AnalysisStatus).*"));
        assertThat(schemas.keySet().stream().filter(name -> name.contains("Page")).toList())
                .containsExactly("PageResponse");
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
                "API_ROUTE_INDEX_NOT_READY",
                "SEMANTIC_BINDING_AMBIGUOUS",
                "SEMANTIC_TARGET_NOT_FOUND",
                "SOURCE_DECLARATION_NOT_FOUND",
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
    void should_parse_nullable_composed_references_from_yaml_tree() {
        assertThat(documentTree.isObject()).isTrue();
        assertNullableComposedReference("GraphNode", "target", "MethodTargetPayload");
        assertNullableComposedReference("GraphNode", "declarationRange", "TextRangePayload");
        assertNullableComposedReference("GraphWarning", "callSite", "SourceRangePayload");
        assertNullableComposedReference("ApiErrorResponse", "target", "MethodTargetPayload");
    }

    @Test
    void should_give_incoming_and_outgoing_requests_identical_validation_and_conversion_contracts() throws Exception {
        assertRequestContract("com.java.semantic.api.dto.AnalyzeOutgoingCallGraphRequest");
        assertRequestContract("com.java.semantic.api.dto.AnalyzeIncomingCallGraphRequest");
    }

    private void assertRequestContract(String requestClassName) throws Exception {
        Class<?> requestType = Class.forName(requestClassName);
        Class<?> targetType = Class.forName("com.java.semantic.api.dto.identity.MethodTargetPayload");
        Constructor<?> targetConstructor = targetType.getConstructor(
                SourceTypeIdentityPayload.class, String.class, List.class);
        SourceTypeIdentityPayload sourceType = new SourceTypeIdentityPayload(
                new JavaTypeIdentityPayload("", "OrderController"),
                "src/main/java/com/example/OrderController.java");
        Object target = targetConstructor.newInstance(
                sourceType,
                "placeOrder",
                List.of("com.example.PlaceOrderRequest"));
        Constructor<?> requestConstructor = requestType.getConstructor(
                String.class, String.class, Integer.class, targetType);
        Object request = requestConstructor.newInstance("order-service", "0000000000000000000000000000000000000000", null, target);

        Method depth = requestType.getMethod("depth");
        assertThat(depth.invoke(request)).isEqualTo(2);
        assertThat(Arrays.stream(targetType.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("toDomain");

        Object invalidTarget = targetConstructor.newInstance(
                new SourceTypeIdentityPayload(new JavaTypeIdentityPayload("", ""), " "),
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
                        "target.sourceType.sourceFile",
                        "target.sourceType.javaType.className",
                        "target.methodName",
                        "target.parameterTypes[0].<list element>");

        assertThatThrownBy(() -> targetConstructor.newInstance(
                null,
                "placeOrder",
                List.of()))
                .hasCauseInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> OBJECT_MAPPER.readValue("""
                {
                  "repoId":"order-service",
                  "expectedRevision":"0000000000000000000000000000000000000000",
                  "target":{
                    "sourceType":{
                      "javaType":{"packageName":"","className":"OrderController"},
                      "sourceFile":"src/main/java/com/example/OrderController.java"
                    },
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
        assertThatThrownBy(() -> new PositionPayload(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionPayload(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        PositionPayload position = new PositionPayload(0, 0);
        TextRangePayload range = new TextRangePayload(position, position);
        assertThatThrownBy(() -> new SourceRangePayload(null, range))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRangePayload("src/Main.java", null))
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
        MethodTargetPayload target = new MethodTargetPayload(
                new SourceTypeIdentityPayload(
                        new JavaTypeIdentityPayload("com.example", "OrderController"),
                        "src/main/java/com/example/OrderController.java"),
                "placeOrder",
                parameterTypes);
        parameterTypes.add("java.lang.String");
        assertThat(target.parameterTypes()).isEmpty();
        assertThatThrownBy(() -> new MethodTargetPayload(
                new SourceTypeIdentityPayload(
                        new JavaTypeIdentityPayload("com.example", "OrderController"),
                        "src/main/java/com/example/OrderController.java"),
                "placeOrder",
                null))
                .isInstanceOf(NullPointerException.class);
        SourceRangePayload range = new SourceRangePayload(
                "src/main/java/com/example/OrderController.java",
                new TextRangePayload(new PositionPayload(0, 0), new PositionPayload(0, 1)));
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

        List<MethodTargetPayload> candidates = new ArrayList<>();
        GraphWarningResponse warning = new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, candidates);
        candidates.add(new MethodTargetPayload(
                new SourceTypeIdentityPayload(
                        new JavaTypeIdentityPayload("com.example", "OrderController"),
                        "src/main/java/com/example/OrderController.java"),
                "placeOrder",
                List.of()));
        assertThat(warning.candidates()).isEmpty();
        assertThatThrownBy(() -> new GraphWarningResponse("AMBIGUOUS", "ambiguous", "node", null, null, null))
                .isInstanceOf(NullPointerException.class);

        GraphNodeResponse node = new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null);
        List<GraphNodeResponse> nodes = new ArrayList<>();
        List<GraphEdgeResponse> edges = new ArrayList<>(List.of(edge));
        List<GraphWarningResponse> warnings = new ArrayList<>(List.of(warning));
        List<GraphErrorResponse> errors = new ArrayList<>(List.of(new GraphErrorResponse("CHILD_FAILURE", "failure", "node")));
        OutgoingCallGraphResponse response = new OutgoingCallGraphResponse(
                "SUCCESS",
                "0000000000000000000000000000000000000000",
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
                "0000000000000000000000000000000000000000",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                null,
                List.of(),
                List.of(),
                List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "0000000000000000000000000000000000000000",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                null,
                List.of(),
                List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "0000000000000000000000000000000000000000",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                null,
                List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingCallGraphResponse(
                "SUCCESS",
                "0000000000000000000000000000000000000000",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                List.of(),
                null))
                .isInstanceOf(NullPointerException.class);

        IncomingCallGraphResponse incomingResponse = new IncomingCallGraphResponse(
                "SUCCESS",
                "0000000000000000000000000000000000000000",
                "node",
                new GraphTraversalResponse(1, 0, 0, true, "NONE"),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertThat(incomingResponse.getClass()).isNotEqualTo(response.getClass());
        assertThat(incomingResponse.nodes()).hasSize(0);

        assertThat(new GraphNodeResponse("node", null, null, "FULL_SOURCE", "EXPANDED", "SYNCHRONOUS", null).target()).isNull();
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
        assertResponseCodes(operation(paths, "/v1/api-routes/lookup", "post"), "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/api-routes/suggest", "post"), "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/discovery/event-listeners", "post"),
                "200", "400", "401", "403", "404", "409", "500");
        assertResponseCodes(operation(paths, "/v1/discovery/method-implementations", "post"),
                "200", "400", "401", "403", "404", "409", "422", "500", "502", "503", "504");
    }

    @Test
    void should_require_preserved_request_bodies_and_revision_pinned_entry_point_queries() {
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
        Map<String, Object> expectedRevision = parameters.stream()
                .map(this::map)
                .filter(parameter -> "expectedRevision".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(expectedRevision).containsEntry("in", "query").containsEntry("required", Boolean.TRUE);
        assertThat(map(expectedRevision.get("schema")))
                .containsEntry("type", "string")
                .containsEntry("pattern", "^[0-9a-f]{40}$");
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
        assertClosedObject(lookupRequest);
        assertThat(required(lookupRequest)).containsExactly("repoId", "expectedRevision", "apiPath");
        assertExactProperties(lookupRequest, "repoId", "expectedRevision", "apiPath", "httpMethod");
        assertThat(schema(properties(lookupRequest), "repoId"))
                .containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(schema(properties(lookupRequest), "expectedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
        assertNonBlankString(properties(lookupRequest), "apiPath");
        assertNullableString(properties(lookupRequest), "httpMethod");

        Map<String, Object> suggestRequest = schema(schemas, "ApiRouteSuggestRequest");
        assertClosedObject(suggestRequest);
        assertThat(required(suggestRequest)).containsExactly("repoId", "expectedRevision", "apiPath", "limit");
        assertExactProperties(suggestRequest, "repoId", "expectedRevision", "apiPath", "httpMethod", "limit");
        assertThat(schema(properties(suggestRequest), "repoId"))
                .containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(schema(properties(suggestRequest), "expectedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
        assertNonBlankString(properties(suggestRequest), "apiPath");
        assertNullableString(properties(suggestRequest), "httpMethod");
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
                "sourceType", "methodName", "analysisTarget", "matchReasons");
        assertThat(schema(properties(candidate), "sourceType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceTypeIdentityPayload"));
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
        assertExactProperties(targetResolution, "status", "target", "candidates", "reasonCode", "availableFollowUps");
        assertThat(required(targetResolution)).containsExactlyInAnyOrder(
                "status", "candidates", "reasonCode", "availableFollowUps");
        assertRequiredNonNullableArray(targetResolution, "availableFollowUps");
        assertThat(list(schema(properties(targetResolution), "status").get("enum")))
                .containsExactly("RESOLVED", "UNRESOLVED", "AMBIGUOUS");
        assertNullableReference(properties(targetResolution), "target", "MethodTargetPayload");
        assertRequiredNonNullableArray(targetResolution, "candidates");
        assertThat(schema(schema(properties(targetResolution), "candidates"), "items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/MethodTargetPayload"));
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
                "sourceType", "description", "basePaths", "methods");
        assertThat(schema(properties(entryPoint), "sourceType"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/SourceTypeIdentityPayload"));
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
        Map<String, Object> page = schema(schemas, "PageResponse");
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
        assertExactPropertiesAndRequired(issue, "code", "count");
        assertThat(list(schema(properties(issue), "code").get("enum")))
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
                "GET_METHOD_SOURCE", "ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH",
                "DISCOVER_METHOD_IMPLEMENTATIONS", "RESOLVE_CONCEPT", "GET_TYPE_MEMBERS");
        assertThat(schema(properties(candidateFollowUp), "request"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ConceptCandidateFollowUpRequestResponse"));

        Map<String, Object> followUp = schema(schemas, "DiscoveryFollowUpResponse");
        assertClosedObject(followUp);
        assertExactPropertiesAndRequired(followUp, "operation", "api", "request");
        assertThat(list(schema(properties(followUp), "operation").get("enum"))).containsExactly(
                "GET_METHOD_SOURCE",
                "ANALYZE_OUTGOING_CALL_GRAPH", "ANALYZE_INCOMING_CALL_GRAPH",
                "DISCOVER_METHOD_IMPLEMENTATIONS", "RESOLVE_CONCEPT", "GET_TYPE_MEMBERS", "DISCOVER_TYPE_MEMBERS",
                "DISCOVER_CONCEPTS", "DISCOVER_EVENT_LISTENERS",
                "RESOLVE_SOURCE_SYMBOL", "FIND_INTERNAL_REFERENCES", "GET_SOURCE_SEGMENT", "GET_EVIDENCE_SOURCE");
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
        Map<String, String> sharedMethodRequest =
                Map.of("$ref", "#/components/schemas/MethodTargetFollowUpRequestResponse");
        assertThat(list(schema(schemas, "ConceptCandidateFollowUpRequestResponse").get("oneOf")))
                .containsOnlyOnce(sharedMethodRequest);
        assertThat(list(schema(schemas, "DiscoveryFollowUpRequestResponse").get("oneOf")))
                .containsOnlyOnce(sharedMethodRequest);
        assertClosedFollowUpRequest(schemas, "AnalyzeCallGraphRequestResponse", "repoId", "expectedRevision", "depth", "target");
        assertClosedFollowUpRequest(schemas, "DiscoverMethodImplementationsRequestResponse",
                "repoId", "expectedRevision", "declarationTarget");
        assertClosedFollowUpRequest(schemas, "ResolveConceptRequestResponse",
                "repoId", "expectedRevision", "identity");
        assertClosedFollowUpRequest(schemas, "DiscoverConceptsRequestResponse",
                "operator", "repoId", "expectedRevision", "terms", "kinds", "packagePrefix", "offset", "limit");
        assertThat(schema(properties(schema(schemas, "DiscoverConceptsRequestResponse")), "kinds"))
                .contains(entry("minItems", 1), entry("uniqueItems", Boolean.TRUE))
                .doesNotContainKey("maxItems");
        assertClosedFollowUpRequest(schemas, "DiscoverEventListenersRequestResponse",
                "repoId", "expectedRevision", "eventType", "offset", "limit");
        assertClosedFollowUpRequest(schemas, "GetTypeMembersRequestResponse",
                "repoId", "expectedRevision", "sourceType", "memberKinds", "namePrefix", "offset", "limit");
        assertClosedFollowUpRequest(schemas, "DiscoverTypeMembersRequestResponse",
                "repoId", "expectedRevision", "sourceType", "memberKinds", "namePrefix", "offset", "limit");
        assertClosedFollowUpRequest(schemas, "FindInternalReferencesRequestResponse",
                "repoId", "expectedRevision", "target", "offset", "limit");
        assertClosedFollowUpRequest(schemas, "GetSourceSegmentRequestResponse",
                "repoId", "expectedRevision", "location", "contextLines");
        assertClosedFollowUpRequest(schemas, "GetEvidenceSourceRequestResponse",
                "repoId", "expectedRevision", "identity");
        assertThat(properties(schema(schemas, "GetTypeMembersRequestResponse")))
                .containsKey("sourceType")
                .doesNotContainKeys("sourceFile", "fullyQualifiedName", "fullyQualifiedTypeName");
    }

    private void assertClosedConceptIdentitySchemas(Map<String, Object> schemas) {
        assertDiscriminatedUnion(
                schemas,
                "ConceptIdentityResponse",
                "kind",
                Map.of(
                        "TYPE", "TypeConceptIdentityResponse",
                        "METHOD", "MethodConceptIdentityResponse",
                        "FIELD", "FieldConceptIdentityResponse",
                        "ANNOTATION_USAGE", "AnnotationUsageConceptIdentityResponse",
                        "TYPE_USAGE", "TypeUsageConceptIdentityResponse",
                        "API_ROUTE", "ApiRouteConceptIdentityResponse",
                        "MQ_DESTINATION", "MqDestinationConceptIdentityResponse",
                        "SCHEDULE", "ScheduleConceptIdentityResponse",
                        "MAPPER_STATEMENT", "MapperStatementConceptIdentityResponse",
                        "MAPPER_STATEMENT_VARIANT", "MapperStatementVariantConceptIdentityResponse"));
        assertDiscriminatedUnion(
                schemas,
                "ConceptCandidateDetailsResponse",
                "kind",
                Map.of(
                        "FIELD", "FieldDetailsResponse",
                        "MAPPER_STATEMENT", "MapperStatementDetailsResponse"));
        assertDiscriminatedUnion(
                schemas,
                "DeclarationSubjectResponse",
                "kind",
                Map.of(
                        "TYPE", "TypeDeclarationSubjectResponse",
                        "METHOD", "ResolvedMethodDeclarationSubjectResponse",
                        "METHOD_UNRESOLVED", "UnresolvedMethodDeclarationSubjectResponse",
                        "FIELD", "FieldDeclarationSubjectResponse"));
        assertDiscriminatedUnion(
                schemas,
                "AnnotationTypeResponse",
                "status",
                Map.of(
                        "RESOLVED", "ResolvedAnnotationTypeResponse",
                        "UNRESOLVED", "UnresolvedAnnotationTypeResponse"));
        assertDiscriminatedUnion(
                schemas,
                "TypeUsagePathResponse",
                "kind",
                Map.of(
                        "TYPE_ARGUMENT", "TypeArgumentPathResponse",
                        "WILDCARD_EXTENDS_BOUND", "WildcardExtendsBoundPathResponse",
                        "WILDCARD_SUPER_BOUND", "WildcardSuperBoundPathResponse",
                        "TYPE_VARIABLE_BOUND", "TypeVariableBoundPathResponse"));

        List<String> closedIdentityObjects = List.of(
                "TypeConceptIdentityResponse",
                "MethodConceptIdentityResponse",
                "FieldConceptIdentityResponse",
                "AnnotationUsageConceptIdentityResponse",
                "TypeUsageConceptIdentityResponse",
                "ApiRouteConceptIdentityResponse",
                "MqDestinationConceptIdentityResponse",
                "ScheduleConceptIdentityResponse",
                "MapperStatementConceptIdentityResponse",
                "MapperStatementVariantConceptIdentityResponse",
                "MapperStatementKeyPayload",
                "MapperStatementIdentityPayload",
                "MapperFragmentIdentityPayload",
                "FieldDetailsResponse",
                "MapperStatementDetailsResponse",
                "TypeDeclarationSubjectResponse",
                "ResolvedMethodDeclarationSubjectResponse",
                "UnresolvedMethodDeclarationSubjectResponse",
                "FieldDeclarationSubjectResponse",
                "ResolvedAnnotationTypeResponse",
                "UnresolvedAnnotationTypeResponse",
                "JavaTypeIdentityPayload",
                "SourceTypeIdentityPayload",
                "TypeMemberIdentityPayload",
                "MethodScopedIdentityPayload",
                "SourceSymbolMethodContextPayload",
                "SourceSymbolContextPayload",
                "TypeUsageLocationResponse",
                "ReferencedTypeResponse",
                "TypeArgumentPathResponse",
                "WildcardExtendsBoundPathResponse",
                "WildcardSuperBoundPathResponse",
                "TypeVariableBoundPathResponse");
        closedIdentityObjects.forEach(name -> assertClosedObject(schema(schemas, name)));

        Map<String, Object> target = schema(schemas, "MethodTargetPayload");
        assertClosedObject(target);
        assertExactPropertiesAndRequired(
                target, "sourceType", "methodName", "parameterTypes");
        assertExactPropertiesAndRequired(
                schema(schemas, "TypeConceptIdentityResponse"), "kind", "sourceType");
        assertExactPropertiesAndRequired(
                schema(schemas, "TypeDeclarationSubjectResponse"), "kind", "sourceType");
        assertExactPropertiesAndRequired(
                schema(schemas, "UnresolvedMethodDeclarationSubjectResponse"), "kind", "target");
        assertExactPropertiesAndRequired(
                schema(schemas, "FieldDeclarationSubjectResponse"), "kind", "identity");
        assertThat(schemas).doesNotContainKeys(
                "MethodTarget",
                "MethodTargetRequest",
                "MethodTargetResponse",
                "JavaTypeIdentityResponse",
                "Position",
                "PositionResponse",
                "SourceRange",
                "SourceRangeResponse",
                "SourceSymbolPositionRequest",
                "SourceSymbolContextRequest",
                "SourceSymbolMethodContextRequest",
                "SourceSymbolContextResponse",
                "SourceSymbolMethodContextResponse",
                "MapperFragmentIdentityRequest",
                "MapperFragmentIdentityResponse",
                "MapperStatementIdentityResponse",
                "MapperStatementKeyResponse",
                "MapperStatementVariantResponse");
    }

    private void assertClosedFieldTypeReferenceSchemas(Map<String, Object> schemas) {
        assertDiscriminatedUnion(
                schemas,
                "FieldTypeReferenceResponse",
                "kind",
                Map.of(
                        "NAMED", "NamedFieldTypeReferenceResponse",
                        "PARAMETERIZED", "ParameterizedFieldTypeReferenceResponse",
                        "PRIMITIVE", "PrimitiveFieldTypeReferenceResponse",
                        "ARRAY", "ArrayFieldTypeReferenceResponse",
                        "WILDCARD", "WildcardFieldTypeReferenceResponse",
                        "TYPE_VARIABLE", "TypeVariableFieldTypeReferenceResponse"));
        List<String> closedFieldTypeObjects = List.of(
                "NamedFieldTypeReferenceResponse",
                "ParameterizedFieldTypeReferenceResponse",
                "PrimitiveFieldTypeReferenceResponse",
                "ArrayFieldTypeReferenceResponse",
                "WildcardFieldTypeReferenceResponse",
                "TypeVariableFieldTypeReferenceResponse");
        closedFieldTypeObjects.forEach(name -> assertClosedObject(schema(schemas, name)));
        Map<String, Object> named = schema(schemas, "NamedFieldTypeReferenceResponse");
        assertExactProperties(named, "kind", "writtenType", "simpleTypeName", "resolvedJavaType", "sourceDefined");
        assertThat(required(named)).containsExactlyInAnyOrder("kind", "writtenType", "simpleTypeName", "sourceDefined");
        assertExactPropertiesAndRequired(
                schema(schemas, "ParameterizedFieldTypeReferenceResponse"),
                "kind", "writtenType", "rawType", "typeArguments");
        assertExactPropertiesAndRequired(
                schema(schemas, "PrimitiveFieldTypeReferenceResponse"), "kind", "writtenType");
        assertExactPropertiesAndRequired(
                schema(schemas, "ArrayFieldTypeReferenceResponse"),
                "kind", "writtenType", "elementType", "dimensions");
        Map<String, Object> wildcard = schema(schemas, "WildcardFieldTypeReferenceResponse");
        assertExactProperties(wildcard, "kind", "writtenType", "upperBound", "lowerBound", "sourceDefined");
        assertThat(required(wildcard)).containsExactlyInAnyOrder("kind", "writtenType", "sourceDefined");
        assertExactPropertiesAndRequired(
                schema(schemas, "TypeVariableFieldTypeReferenceResponse"),
                "kind", "writtenType", "variableName", "upperBounds", "sourceDefined");
    }

    private void assertDiscriminatedUnion(
            Map<String, Object> schemas,
            String schemaName,
            String discriminatorProperty,
            Map<String, String> variants) {
        Map<String, Object> union = schema(schemas, schemaName);
        assertThat(union).containsOnlyKeys("oneOf", "discriminator");
        List<Map<String, String>> references = variants.values().stream()
                .map(name -> Map.of("$ref", "#/components/schemas/" + name))
                .toList();
        assertThat(list(union.get("oneOf"))).containsExactlyInAnyOrderElementsOf(references);
        Map<String, String> mapping = variants.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> "#/components/schemas/" + entry.getValue()));
        assertThat(schema(union, "discriminator")).containsEntry("propertyName", discriminatorProperty);
        assertThat(map(schema(union, "discriminator").get("mapping"))).containsExactlyInAnyOrderEntriesOf(mapping);
    }

    private void assertClosedFollowUpRequest(Map<String, Object> schemas, String name, String... properties) {
        Map<String, Object> request = schema(schemas, name);
        assertClosedObject(request);
        assertExactProperties(request, properties);
    }

    private void assertSourceOperation(
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

    private void assertClosedSourceRequest(
            Map<String, Object> schemas,
            String schemaName,
            String authorityName) {
        Map<String, Object> request = schema(schemas, schemaName);
        assertClosedObject(request);
        assertExactPropertiesAndRequired(request, "repoId", "expectedRevision", authorityName);
        assertThat(schema(properties(request), "expectedRevision"))
                .containsEntry("pattern", REVISION_PATTERN);
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
            String ownerSchemaName,
            String propertyName,
            String referencedSchemaName) {
        JsonNode propertySchema = documentTree.path("components")
                .path("schemas")
                .path(ownerSchemaName)
                .path("properties")
                .path(propertyName);
        assertThat(propertySchema.isObject()).isTrue();
        assertThat(propertySchema.path("nullable").asBoolean()).isTrue();
        JsonNode allOf = propertySchema.path("allOf");
        assertThat(allOf.isArray()).isTrue();
        assertThat(allOf.size()).isEqualTo(1);
        assertThat(allOf.get(0).path("$ref").asString())
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

    private long occurrenceCount(String value, String marker) {
        return Pattern.compile(Pattern.quote(marker)).matcher(value).results().count();
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
