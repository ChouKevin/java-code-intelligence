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
                "/v1/repositories/{repoId}/checkout"));
        assertThat(map(paths.get("/v1/repositories"))).containsOnlyKeys("get");
        assertThat(map(paths.get("/v1/repositories/{repoId}"))).containsOnlyKeys("parameters", "get");
        assertThat(map(paths.get("/v1/repositories/{repoId}/ensure")))
                .containsOnlyKeys("parameters", "post");
        assertThat(map(paths.get("/v1/repositories/{repoId}/sync")))
                .containsOnlyKeys("parameters", "post");
        assertThat(map(paths.get("/v1/repositories/{repoId}/checkout")))
                .containsOnlyKeys("parameters", "post");

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
    }

    @Test
    void should_describe_exact_api_token_security_when_contract_is_loaded() {
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
    }

    @Test
    void should_require_exact_request_bodies_when_mutation_contract_is_loaded() {
        Map<String, Object> paths = map(document.get("paths"));
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/sync", "post"),
                "#/components/schemas/SyncRepositoryRequest");
        assertRequiredRequestBody(
                operation(paths, "/v1/repositories/{repoId}/checkout", "post"),
                "#/components/schemas/CheckoutRepositoryRequest");
    }

    @Test
    void should_describe_safe_and_validated_schemas_when_contract_is_loaded() {
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
                .containsEntry("pattern", "^[0-9a-f]{40}$|^FIXTURE$");

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
