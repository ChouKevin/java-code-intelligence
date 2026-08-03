package com.java.semantic.mcp;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class McpQueryRegistryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpToolCatalogConfiguration.class)
            .withBean(ObjectMapper.class, JsonMapper::new)
            .withBean(Validator.class, () -> Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void should_reject_duplicate_tool_names_at_startup() {
        McpQueryProvider firstProvider = () -> List.of(registration("semantic_list_repositories"));
        McpQueryProvider duplicateProvider = () -> List.of(registration("semantic_list_repositories"));

        assertStartupFails(List.of(firstProvider, duplicateProvider), "duplicate");
    }

    @Test
    void should_reject_invalid_tool_names_at_startup() {
        McpQueryProvider provider = () -> List.of(registration("semantic-list-repositories"));

        assertStartupFails(List.of(provider), "invalid");
    }

    @Test
    void should_reject_a_catalog_that_differs_from_the_canonical_allowlist_at_startup() {
        McpQueryProvider provider = () -> McpQueryRegistry.canonicalToolNames().stream()
                .filter(name -> !name.equals("semantic_get_evidence_source"))
                .map(McpQueryRegistryTest::registration)
                .toList();

        assertStartupFails(List.of(provider), "canonical allowlist");
    }

    @Test
    void should_expose_the_complete_catalog_in_canonical_name_order() {
        McpQueryProvider provider = () -> McpQueryRegistry.canonicalToolNames().reversed().stream()
                .map(McpQueryRegistryTest::registration)
                .toList();

        McpQueryRegistry registry = new McpQueryRegistry(List.of(provider));

        assertThat(registry.registrations())
                .extracting(McpQueryRegistration::name)
                .containsExactlyElementsOf(McpQueryRegistry.canonicalToolNames());
    }

    @Test
    void should_keep_read_only_metadata_out_of_provider_registrations() {
        assertThat(McpQueryRegistration.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("name", "description", "inputType", "outputType", "handler")
                .doesNotContain("readOnly", "readOnlyHint", "destructiveHint", "idempotentHint");
    }

    @Test
    void should_assign_fixed_query_annotations_during_mcp_projection() {
        McpQueryProvider provider = () -> McpQueryRegistry.canonicalToolNames().stream()
                .map(McpQueryRegistryTest::registration)
                .toList();
        McpQueryRegistry registry = new McpQueryRegistry(List.of(provider));
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        McpToolCatalogConfiguration configuration = new McpToolCatalogConfiguration();

        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = configuration.mcpQueryToolSpecifications(
                registry,
                new StrictMcpToolInputDecoder(new JsonMapper(), validator),
                new McpQuerySchemaFactory(),
                new JsonMapper());

        assertThat(specifications)
                .extracting(specification -> specification.tool().name())
                .containsExactlyElementsOf(McpQueryRegistry.canonicalToolNames());
        assertThat(specifications).allSatisfy(specification -> {
            assertThat(specification.tool().annotations().readOnlyHint()).isTrue();
            assertThat(specification.tool().annotations().destructiveHint()).isFalse();
            assertThat(specification.tool().annotations().idempotentHint()).isTrue();
        });
    }

    private static McpQueryRegistration<String, String> registration(String name) {
        return new McpQueryRegistration<>(name, "description", String.class, String.class, Function.identity());
    }

    private void assertStartupFails(List<McpQueryProvider> providers, String message) {
        contextRunner
                .withBean(McpQueryProvider.class, () -> () -> providers.stream()
                        .flatMap(provider -> provider.registrations().stream())
                        .toList())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(message);
                });
    }
}
