package com.java.semantic.config;

import com.java.semantic.callgraph.application.DirectCallRelationshipResolver;
import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SemanticAnalysisConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_register_analysis_beans_when_java_semantic_service_is_available() {
        contextRunner
                .withBean(JavaSemanticService.class, () -> mock(JavaSemanticService.class))
                .withBean(RepositoryApplicationService.class, () -> mock(RepositoryApplicationService.class))
                .withBean(SyntaxExtractionService.class, () -> mock(SyntaxExtractionService.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DirectCallRelationshipResolver.class);
                    assertThat(context).hasSingleBean(SemanticCallGraphBuilder.class);
                    assertThat(context).hasSingleBean(IncomingSemanticCallGraphBuilder.class);
                    assertThat(context).hasSingleBean(SemanticAnalysisApplicationService.class);
                });
    }

    @Test
    void should_not_register_analysis_beans_when_java_semantic_service_is_unavailable() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DirectCallRelationshipResolver.class);
            assertThat(context).doesNotHaveBean(SemanticCallGraphBuilder.class);
            assertThat(context).doesNotHaveBean(IncomingSemanticCallGraphBuilder.class);
            assertThat(context).doesNotHaveBean(SemanticAnalysisApplicationService.class);
        });
    }
}
