package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void advertises_only_the_twelve_application_routes_and_shared_application_errors() throws Exception {
        Map<String, Object> root = map(new Yaml().load(Files.readString(Path.of("src", "main", "resources", "openapi",
                "semantic-api-v1.yaml"))));
        Map<String, Object> paths = map(root.get("paths"));
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));

        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                "/api/v1/repositories",
                "/api/v1/repositories/{repositoryId}",
                "/api/v1/search-code",
                "/api/v1/fact-source",
                "/api/v1/entry-points",
                "/api/v1/api-routes",
                "/api/v1/event-listeners",
                "/api/v1/type-members",
                "/api/v1/method-implementations",
                "/api/v1/references",
                "/api/v1/callers",
                "/api/v1/callees");
        assertThat(schemas).containsKeys("SemanticQueryError", "RepositoryCollection", "RepositoryItem", "CollectionResult",
                "FactSourceResult", "SearchCodeRequest", "FactSourceRequest", "EntryPointRequest", "ApiRouteRequest",
                "EventListenerRequest", "TypeMemberRequest", "RelationRequest");
        assertThat(map(schemas.get("SemanticQueryError")).get("properties")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("code", "message", "retryable", "currentRevision");
    }

    @Test
    void publishes_closed_defaults_and_the_full_http_method_enum() throws Exception {
        Map<String, Object> root = map(new Yaml().load(Files.readString(Path.of("src", "main", "resources", "openapi",
                "semantic-api-v1.yaml"))));
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));

        for (String schema : Set.of("SearchCodeRequest", "FactSourceRequest", "EntryPointRequest", "ApiRouteRequest",
                "EventListenerRequest", "TypeMemberRequest", "RelationRequest")) {
            assertThat(map(schemas.get(schema))).containsEntry("additionalProperties", false);
        }
        Map<String, Object> routeProperties = map(map(schemas.get("ApiRouteRequest")).get("properties"));
        assertThat(map(routeProperties.get("httpMethod")).get("enum")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "ALL");
        Map<String, Object> searchProperties = map(map(schemas.get("SearchCodeRequest")).get("properties"));
        assertThat(map(searchProperties.get("offset"))).containsEntry("default", 0);
        assertThat(map(searchProperties.get("limit"))).containsEntry("default", 20).containsEntry("maximum", 100);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
