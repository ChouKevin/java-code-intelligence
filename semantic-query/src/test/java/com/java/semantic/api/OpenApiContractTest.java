package com.java.semantic.api;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
        assertThat(schemas).containsKeys("SemanticQueryError", "RepositoryCollection", "RepositoryItem",
                "FactSourceResult", "SearchCodeRequest", "FactSourceRequest", "EntryPointRequest", "ApiRouteRequest",
                "EventListenerRequest", "TypeMemberRequest", "RelationRequest", "ProgramElement", "EntryPointItem",
                "Trigger", "EventListenerItem", "ImplementationItem", "CallerItem", "CalleeItem", "ReferenceItem", "RelationSite");
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
        assertThat(map(searchProperties.get("query"))).containsEntry("minLength", CodeFactSearchQuery.MIN_QUERY_LENGTH)
                .containsEntry("maxLength", CodeFactSearchQuery.MAX_QUERY_LENGTH);
        assertThat(map(searchProperties.get("packagePrefix"))).containsEntry("pattern",
                "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
        assertThat(map(map(searchProperties.get("kinds")).get("items")).get("enum"))
                .isEqualTo(Arrays.stream(CodeFactKind.values()).map(Enum::name).toList());
    }

    @Test
    void publishes_operation_specific_collection_item_schemas() throws Exception {
        Map<String, Object> root = openApi();
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        Map<String, String> operationSchemas = Map.of(
                "/api/v1/search-code", "SearchCodeResult",
                "/api/v1/entry-points", "EntryPointCollectionResult",
                "/api/v1/api-routes", "EntryPointCollectionResult",
                "/api/v1/event-listeners", "EventListenerCollectionResult",
                "/api/v1/type-members", "TypeMemberCollectionResult",
                "/api/v1/method-implementations", "ImplementationCollectionResult",
                "/api/v1/references", "ReferenceCollectionResult",
                "/api/v1/callers", "CallerCollectionResult",
                "/api/v1/callees", "CalleeCollectionResult");
        Map<String, String> itemSchemas = Map.of(
                "/api/v1/search-code", "ProgramElement",
                "/api/v1/entry-points", "EntryPointItem",
                "/api/v1/api-routes", "EntryPointItem",
                "/api/v1/event-listeners", "EventListenerItem",
                "/api/v1/type-members", "ProgramElement",
                "/api/v1/method-implementations", "ImplementationItem",
                "/api/v1/references", "ReferenceItem",
                "/api/v1/callers", "CallerItem",
                "/api/v1/callees", "CalleeItem");

        for (Map.Entry<String, String> operation : operationSchemas.entrySet()) {
            Map<String, Object> responseSchema = responseSchema(root, operation.getKey());
            assertThat(responseSchema).containsEntry("$ref", "#/components/schemas/" + operation.getValue());
            Map<String, Object> collectionSchema = map(schemas.get(operation.getValue()));
            assertThat(collectionSchema.get("required")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .contains("repositoryId", "revision", "items", "page");
            Map<String, Object> collectionProperties = map(collectionSchema.get("properties"));
            assertThat(map(map(collectionProperties.get("items")).get("items")))
                    .containsEntry("$ref", "#/components/schemas/" + itemSchemas.get(operation.getKey()));
        }

        assertRequired(schemas, "ProgramElement", List.of("displayName"));
        assertRequired(schemas, "EntryPointItem", List.of("factId", "handler", "trigger"));
        assertRequired(schemas, "Trigger", List.of("kind", "method", "value"));
        assertRequired(schemas, "EventListenerItem", List.of("eventType", "handler"));
        assertRequired(schemas, "ImplementationItem", List.of("implementation", "relationKind"));
        assertRequired(schemas, "ReferenceItem", List.of("container", "referenceSite"));
        assertRequired(schemas, "CallerItem", List.of("caller", "callSite"));
        assertRequired(schemas, "CalleeItem", List.of("callee", "callSite"));
        assertRequired(schemas, "RelationSite", List.of("factId", "source"));
    }

    private static void assertRequired(Map<String, Object> schemas, String schemaName, List<String> required) {
        assertThat(map(schemas.get(schemaName)).get("required")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsAll(required);
    }

    private static Map<String, Object> responseSchema(Map<String, Object> root, String path) {
        Map<String, Object> pathItem = pathItem(root, path);
        Map<String, Object> post = map(pathItem.get("post"));
        Map<String, Object> response = map(map(post.get("responses")).get("200"));
        return map(map(map(response.get("content")).get("application/json")).get("schema"));
    }

    private static Map<String, Object> pathItem(Map<String, Object> root, String path) {
        Map<String, Object> value = map(map(root.get("paths")).get(path));
        Object reference = value.get("$ref");
        if (reference instanceof String pathItemReference) {
            String name = pathItemReference.substring("#/components/pathItems/".length());
            return map(map(map(root.get("components")).get("pathItems")).get(name));
        }
        return value;
    }

    private static Map<String, Object> openApi() throws Exception {
        return map(new Yaml().load(Files.readString(Path.of("src", "main", "resources", "openapi", "semantic-api-v1.yaml"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
