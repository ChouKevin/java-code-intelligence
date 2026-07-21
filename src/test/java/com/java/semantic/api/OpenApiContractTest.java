package com.java.semantic.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class OpenApiContractTest {

    private static final String REVISION_PATTERN = "^[0-9a-f]{40}$|^FIXTURE$";

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
    void should_describe_exact_path_methods_and_responses_when_contract_is_loaded() {
        Map<String, Object> paths = map(document.get("paths"));

        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(Set.of(
                "/v1/repositories",
                "/v1/repositories/{repoId}",
                "/v1/repositories/{repoId}/ensure",
                "/v1/repositories/{repoId}/sync",
                "/v1/repositories/{repoId}/checkout",
                "/v1/repositories/{repoId}/entry-points",
                "/v1/analyses/call-graph",
                "/v1/analyses/call-graph/flatten",
                "/v1/api-routes/lookup",
                "/v1/api-routes/suggest"));
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
        assertThat(map(paths.get("/v1/analyses/call-graph"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/analyses/call-graph/flatten"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/api-routes/lookup"))).containsOnlyKeys("post");
        assertThat(map(paths.get("/v1/api-routes/suggest"))).containsOnlyKeys("post");

        assertResponseCodes(operation(paths, "/v1/repositories", "get"),
                "200", "401", "403", "409");
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
        assertResponseCodes(operation(paths, "/v1/analyses/call-graph", "post"),
                "200", "400", "401", "403", "404", "409", "422", "500", "503", "504");
        assertResponseCodes(operation(paths, "/v1/analyses/call-graph/flatten", "post"),
                "200", "400", "401", "403", "404", "409", "422", "500", "503", "504");
        assertResponseCodes(operation(paths, "/v1/api-routes/lookup", "post"),
                "200", "400", "401", "403", "500");
        assertResponseCodes(operation(paths, "/v1/api-routes/suggest", "post"),
                "200", "400", "401", "403", "500");
    }

    @Test
    void should_require_exact_request_bodies_and_entry_point_query_parameter() {
        Map<String, Object> paths = map(document.get("paths"));
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/sync", "post"),
                "#/components/schemas/SyncRepositoryRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/checkout", "post"),
                "#/components/schemas/CheckoutRepositoryRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/analyses/call-graph", "post"),
                "#/components/schemas/AnalyzeCallGraphRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/analyses/call-graph/flatten", "post"),
                "#/components/schemas/AnalyzeCallGraphRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/api-routes/lookup", "post"),
                "#/components/schemas/ApiRouteLookupRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/api-routes/suggest", "post"),
                "#/components/schemas/ApiRouteSuggestRequest");

        Map<String, Object> entryPointOperation = operation(
                paths, "/v1/repositories/{repoId}/entry-points", "get");
        List<Object> parameters = list(entryPointOperation.get("parameters"));
        assertThat(parameters).isNotEmpty();
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
    void should_describe_exact_security_and_request_schemas() {
        List<Object> security = list(document.get("security"));
        assertThat(security).hasSize(1);
        Map<String, Object> securityRequirement = map(security.getFirst());
        assertThat(securityRequirement).containsOnlyKeys("ApiToken");
        assertThat(list(securityRequirement.get("ApiToken"))).hasSize(0);

        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemes = map(components.get("securitySchemes"));
        assertThat(schemes).containsOnlyKeys("ApiToken");
        assertThat(map(schemes.get("ApiToken")))
                .containsOnly(
                        entry("type", "apiKey"),
                        entry("in", "header"),
                        entry("name", "X-Api-Token"));

        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> analyzeRequest = map(schemas.get("AnalyzeCallGraphRequest"));
        assertThat(list(analyzeRequest.get("required"))).containsExactly(
                "repoId", "packageName", "className", "methodSignature");
        assertThat(map(analyzeRequest.get("properties")).keySet()).containsExactlyInAnyOrder(
                "repoId", "packageName", "className", "methodSignature", "expectedRevision");
        Map<String, Object> analyzeProperties = map(analyzeRequest.get("properties"));
        assertThat(map(analyzeProperties.get("repoId")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(map(analyzeProperties.get("packageName")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        assertThat(map(analyzeProperties.get("className")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        assertThat(map(analyzeProperties.get("methodSignature")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        assertThat(map(analyzeProperties.get("expectedRevision")))
                .containsEntry("nullable", Boolean.TRUE)
                .containsEntry("pattern", REVISION_PATTERN);
        assertThat(analyzeProperties).doesNotContainKeys("output", "outputMode", "depth", "maxDepth");

        Map<String, Object> lookupRequest = map(schemas.get("ApiRouteLookupRequest"));
        assertThat(list(lookupRequest.get("required"))).containsExactly("apiPath");
        Map<String, Object> lookupProperties = map(lookupRequest.get("properties"));
        assertThat(lookupProperties.keySet()).containsExactlyInAnyOrder(
                "apiPath", "httpMethod", "repoScope");
        assertThat(map(lookupProperties.get("apiPath")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        Map<String, Object> lookupHttpMethod = map(lookupProperties.get("httpMethod"));
        assertThat(lookupHttpMethod).containsOnly(
                entry("type", "string"),
                entry("nullable", Boolean.TRUE));
        Map<String, Object> lookupRepoScope = map(lookupProperties.get("repoScope"));
        assertThat(lookupRepoScope).containsOnly(
                entry("type", "string"),
                entry("nullable", Boolean.TRUE));

        Map<String, Object> suggestRequest = map(schemas.get("ApiRouteSuggestRequest"));
        assertThat(list(suggestRequest.get("required"))).containsExactly("apiPath", "limit");
        Map<String, Object> suggestProperties = map(suggestRequest.get("properties"));
        assertThat(map(suggestProperties.get("apiPath")))
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");
        Map<String, Object> suggestHttpMethod = map(suggestProperties.get("httpMethod"));
        assertThat(suggestHttpMethod).containsOnly(
                entry("type", "string"),
                entry("nullable", Boolean.TRUE));
        Map<String, Object> suggestRepoScope = map(suggestProperties.get("repoScope"));
        assertThat(suggestRepoScope).containsOnly(
                entry("type", "string"),
                entry("nullable", Boolean.TRUE));
        Map<String, Object> limit = map(map(suggestRequest.get("properties")).get("limit"));
        assertThat(limit).containsEntry("minimum", 1).containsEntry("maximum", 20);
    }

    @Test
    void should_pin_analysis_graph_envelopes_and_resolution_evidence() {
        Map<String, Object> schemas = schemas();
        assertThat(list(map(schemas.get("AnalysisStatus")).get("enum"))).containsExactly(
                "SUCCESS", "PARTIAL", "FAILED", "BUSINESS_READ_FORBIDDEN");
        assertThat(list(map(schemas.get("ResolutionStrategy")).get("enum"))).containsExactly(
                "JDT_CALL_HIERARCHY",
                "JDT_DEFINITION_FALLBACK",
                "SPRING_BEAN_BY_QUALIFIER",
                "SPRING_BEAN_BY_PRIMARY",
                "SPRING_SINGLE_IMPLEMENTATION",
                "MYBATIS_MAPPER",
                "LOMBOK_GENERATED",
                "EXTERNAL_LIBRARY",
                "FEIGN_CLIENT",
                "BUSINESS_READ_FORBIDDEN",
                "SPRING_MULTIPLE_CANDIDATES",
                "DATA_ACCESS_WITHOUT_EVIDENCE",
                "UNRESOLVED_TARGET");

        assertAnalysisEnvelope(schemas, "ExplainableAnalysisResponse", "ExplainableCallGraph");
        assertAnalysisEnvelope(schemas, "FlattenedAnalysisResponse", "FlattenedCallGraph");
        assertThat(map(map(schemas.get("CallEdge")).get("properties")).get("resolutionStrategy"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/ResolutionStrategy"));
        assertThat(map(map(schemas.get("AnalysisResponse")).get("properties")).get("status"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/AnalysisStatus"));
    }

    @Test
    void should_pin_route_and_error_response_shapes() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> candidate = map(schemas.get("ApiRouteCandidateResponse"));
        assertThat(list(candidate.get("required"))).containsExactly(
                "repoId", "analyzedRevision", "httpMethod", "routeTemplate",
                "packageName", "className", "methodName");
        assertThat(map(candidate.get("properties")).keySet()).containsExactlyInAnyOrder(
                "repoId", "analyzedRevision", "httpMethod", "routeTemplate",
                "packageName", "className", "methodName");
        assertThat(map(map(candidate.get("properties")).get("analyzedRevision")))
                .containsEntry("pattern", REVISION_PATTERN);

        Map<String, Object> routeResponse = map(schemas.get("ApiRouteCandidatesResponse"));
        assertThat(list(routeResponse.get("required"))).containsExactly("candidates");
        assertThat(map(routeResponse.get("properties")).keySet()).containsExactly("candidates");
        assertThat(map(map(schemas.get("ApiErrorResponse")).get("properties")).keySet())
                .contains("candidates");
        assertThat(map(map(schemas.get("ApiErrorResponse")).get("properties")).get("currentRevision"))
                .isEqualTo(Map.of("type", "string", "nullable", true, "pattern", REVISION_PATTERN));
        assertThat(map(map(schemas.get("ApiErrorResponse")).get("properties")).get("expectedRevision"))
                .isEqualTo(Map.of("type", "string", "nullable", true, "pattern", REVISION_PATTERN));
        assertThat(map(map(schemas.get("ApiErrorResponse")).get("properties")).get("analyzedRevision"))
                .isNull();
    }

    @Test
    void should_pin_entry_point_variants_and_safe_fields() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> method = map(schemas.get("EntryPointMethodResponse"));
        assertThat(method.get("oneOf")).isEqualTo(List.of(
                Map.of("$ref", "#/components/schemas/ApiEntryPointMethodResponse"),
                Map.of("$ref", "#/components/schemas/MqEntryPointMethodResponse"),
                Map.of("$ref", "#/components/schemas/ScheduleEntryPointMethodResponse")));
        assertThat(map(method.get("discriminator"))).containsEntry("propertyName", "type");
        assertThat(map(map(method.get("discriminator")).get("mapping"))).containsExactly(
                entry("API", "#/components/schemas/ApiEntryPointMethodResponse"),
                entry("MQ", "#/components/schemas/MqEntryPointMethodResponse"),
                entry("SCHEDULE", "#/components/schemas/ScheduleEntryPointMethodResponse"));

        Map<String, Object> entryPoint = map(schemas.get("EntryPointClassResponse"));
        assertThat(map(entryPoint.get("properties")).keySet()).containsExactlyInAnyOrder(
                "className", "packageName", "packagePath", "description", "basePaths", "methods");
        assertThat(map(map(entryPoint.get("properties")).get("methods")).get("items"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/EntryPointMethodResponse"));
        assertThat(map(entryPoint.get("properties")).keySet())
                .doesNotContain("sourceRoot", "path", "uri", "sourceText", "annotations", "sql");
    }

    @Test
    void should_describe_safe_and_validated_repository_schemas_when_contract_is_loaded() {
        Map<String, Object> components = map(document.get("components"));
        Map<String, Object> schemas = map(components.get("schemas"));
        Map<String, Object> status = map(schemas.get("RepositoryStatusResponse"));
        Map<String, Object> properties = map(status.get("properties"));

        assertThat(list(status.get("required"))).containsExactlyInAnyOrder(
                "repoId", "mode", "displayName", "cloned");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "repoId", "mode", "displayName", "currentBranch", "currentRevision", "cloned");
        assertThat(properties).doesNotContainKeys(
                "sourceRoot", "path", "url", "credential", "git", "uri");
        assertThat(map(properties.get("currentBranch"))).containsEntry("nullable", Boolean.TRUE);
        assertThat(map(properties.get("currentRevision")))
                .containsEntry("nullable", Boolean.TRUE)
                .containsEntry("pattern", REVISION_PATTERN);

        Map<String, Object> checkout = map(schemas.get("CheckoutRepositoryRequest"));
        assertThat(list(checkout.get("required"))).containsExactly("revision");
        Map<String, Object> revision = map(map(checkout.get("properties")).get("revision"));
        assertThat(revision)
                .containsEntry("minLength", 1)
                .containsEntry("pattern", ".*\\S.*");

        Map<String, Object> error = map(schemas.get("ApiErrorResponse"));
        assertThat(list(error.get("required"))).containsExactlyInAnyOrder("errorCode", "message");
        Map<String, Object> errorProperties = map(error.get("properties"));
        assertThat(map(errorProperties.get("currentRevision"))).containsEntry("nullable", Boolean.TRUE);
        assertThat(map(errorProperties.get("expectedRevision"))).containsEntry("nullable", Boolean.TRUE);

        Map<String, Object> repoId = map(map(components.get("parameters")).get("RepoId"));
        assertThat(map(repoId.get("schema")))
                .containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
    }

    private Map<String, Object> operation(
            Map<String, Object> paths,
            String path,
            String method) {
        return map(map(paths.get(path)).get(method));
    }

    private Map<String, Object> schemas() {
        return map(map(document.get("components")).get("schemas"));
    }

    private void assertAnalysisEnvelope(
            Map<String, Object> schemas,
            String schemaName,
            String dataSchemaName) {
        Map<String, Object> envelope = map(schemas.get(schemaName));
        assertThat(list(envelope.get("required"))).containsExactly(
                "status", "data", "warnings", "errors", "metadata", "analyzedRevision");
        Map<String, Object> properties = map(envelope.get("properties"));
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "status", "data", "warnings", "errors", "metadata", "analyzedRevision");
        assertThat(properties.get("status"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/AnalysisStatus"));
        Map<String, Object> data = map(properties.get("data"));
        assertThat(list(data.get("allOf")).getFirst())
                .isEqualTo(Map.of("$ref", "#/components/schemas/" + dataSchemaName));
        assertThat(map(properties.get("analyzedRevision")))
                .containsEntry("pattern", REVISION_PATTERN);
    }

    private void assertResponseCodes(Map<String, Object> operation, String... responseCodes) {
        assertThat(map(operation.get("responses")).keySet())
                .containsExactlyInAnyOrder(responseCodes);
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
