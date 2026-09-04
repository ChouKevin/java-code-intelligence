package com.java.semantic.query;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SemanticQueryRuntimeDependencyTest {

    @Test
    void provides_the_spring_ai_json_mapper_runtime_dependency() {
        assertThatCode(() -> Class.forName("org.springframework.ai.util.JacksonUtils"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper"))
                .doesNotThrowAnyException();
    }

    @Test
    void starts_without_mongo_connectivity_or_online_language_server_and_publishes_twelve_tools() {
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SemanticQueryApplication.class)
                .withPropertyValues("spring.mongodb.uri=mongodb://127.0.0.1:1/semantic-query-unavailable")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object catalog = context.getBean("mcpQueryToolSpecifications");
                    assertThat(catalog).isInstanceOf(List.class);
                    assertThat((List<?>) catalog).hasSize(12).allSatisfy(tool ->
                            assertThat(tool).isInstanceOf(McpStatelessServerFeatures.SyncToolSpecification.class));
                    assertThat(context.getBeansOfType(Object.class).values())
                            .extracting(bean -> bean.getClass().getName())
                            .noneMatch(name -> name.contains(".indexer.") || name.contains(".semantic.adapter.jdtls.")
                                    || name.startsWith("org.eclipse.jdt.") || name.startsWith("org.eclipse.lsp4j."));
                });
    }
}
