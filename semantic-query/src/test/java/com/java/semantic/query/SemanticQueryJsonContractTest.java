package com.java.semantic.query;

import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.SemanticQueryFacade;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SemanticQueryJsonContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void omits_absent_optional_values_from_query_responses() {
        contextRunner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);

            assertThat(mapper.writeValueAsString(new OptionalResponse(Optional.empty())))
                    .isEqualTo("{}");
        });
    }

    @Test
    void creates_the_mcp_catalog_bean_with_the_application_jackson_mapper() {
        contextRunner.withUserConfiguration(QueryMcpToolCatalogConfiguration.class)
                .withBean(SemanticQueryFacade.class, () -> mock(SemanticQueryFacade.class))
                .run(context -> {
                    Object catalog = context.getBean("mcpQueryToolSpecifications");
                    assertThat(catalog).isInstanceOf(List.class);
                    assertThat((List<?>) catalog).hasSize(12);
                });
    }

    private record OptionalResponse(Optional<String> packagePrefix) {
    }
}
