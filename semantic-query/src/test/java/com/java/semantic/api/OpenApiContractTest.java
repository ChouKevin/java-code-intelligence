package com.java.semantic.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void advertises_the_exact_flat_requests_security_typed_errors_and_generation_envelopes() throws Exception {
        String specification = Files.readString(Path.of("src", "main", "resources", "openapi", "semantic-api-v1.yaml"));

        assertThat(specification).contains("security:", "ApiToken:", "name: X-Api-Token")
                .doesNotContain("allOf:")
                .contains("SearchCodeFactsRequest:", "GetCodeFactRequest:", "CallGraphRequest:", "RelationRequest:",
                        "EventListenerRequest:", "TypeMemberRequest:", "SourceSymbolRequest:", "SourceSegmentRequest:")
                .contains("additionalProperties: false", "required: [repositoryId, revision, result]",
                        "RevisionOutdatedError:", "QueryFailure:", "repositoryId", "requestedRevision", "currentRevision",
                        "/v1/analyses/call-graphs/outgoing:", "/v1/discovery/type-members:",
                        "/v1/discovery/source-symbols/resolve:", "/v1/api-routes/lookup:")
                .contains("'400': {$ref: '#/components/responses/RequestInvalid'}",
                        "'404': {$ref: '#/components/responses/QueryNotFound'}",
                        "'409': {$ref: '#/components/responses/RevisionOutdated'}",
                        "'503': {$ref: '#/components/responses/QueryUnavailable'}");
    }

    @Test
    void parses_and_advertises_exact_input_contracts_member_kinds_and_repository_failure_responses() throws Exception {
        String specification = Files.readString(Path.of("src", "main", "resources", "openapi", "semantic-api-v1.yaml"));
        Map<String, Object> root = map(new Yaml().load(specification));
        Map<String, Object> paths = map(root.get("paths"));
        assertThat(responses(paths, "/v1/repositories")).containsKeys("200", "401", "403", "503");
        assertThat(responses(paths, "/v1/repositories/{repositoryId}")).containsKeys("200", "400", "401", "403", "404", "503");
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        Map<String, Object> searchProperties = map(map(schemas.get("SearchCodeFactsRequest")).get("properties"));
        assertThat(map(searchProperties.get("repositoryId"))).containsEntry("pattern", "^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(map(searchProperties.get("revision"))).containsEntry("pattern", "^[0-9a-f]{40}$");
        assertThat(map(searchProperties.get("query"))).containsEntry("minLength", 2).containsEntry("maxLength", 256);
        Map<String, Object> factProperties = map(map(schemas.get("GetCodeFactRequest")).get("properties"));
        assertThat(map(factProperties.get("factId"))).containsEntry("pattern", "^[0-9a-f]{64}$");
        Map<String, Object> typeMemberProperties = map(map(schemas.get("TypeMemberRequest")).get("properties"));
        assertThat(stringList(map(map(typeMemberProperties.get("kinds")).get("items")).get("enum")))
                .containsExactlyInAnyOrder("METHOD", "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> responses(Map<String, Object> paths, String path) {
        return map(map(paths.get(path)).get("get")).get("responses") instanceof Map<?, ?> values ? map(values) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }
}
