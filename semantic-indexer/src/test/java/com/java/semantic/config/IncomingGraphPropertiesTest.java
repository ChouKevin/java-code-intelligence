package com.java.semantic.config;

import com.java.semantic.callgraph.application.DirectCallRelationshipResolver;
import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.semantic.domain.JavaSemanticService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IncomingGraphPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_default_incoming_depth_two_budget_independently() {
        contextRunner.run(context -> {
            IncomingGraphProperties properties = context.getBean(IncomingGraphProperties.class);

            assertThat(properties.depthTwoNodeBudget()).isEqualTo(40);
        });
    }

    @Test
    void should_bind_incoming_depth_two_budget_without_changing_outgoing_budget() {
        contextRunner
                .withPropertyValues(
                        "semantic.analysis.incoming.depth-two-node-budget=13",
                        "semantic.analysis.outgoing.depth-two-node-budget=7")
                .run(context -> {
                    IncomingGraphProperties incoming = context.getBean(IncomingGraphProperties.class);
                    OutgoingGraphProperties outgoing = context.getBean(OutgoingGraphProperties.class);

                    assertThat(incoming.depthTwoNodeBudget()).isEqualTo(13);
                    assertThat(outgoing.depthTwoNodeBudget()).isEqualTo(7);
                });
    }

    @Test
    void should_create_one_shared_relationship_resolver_for_both_graph_builders() {
        new ApplicationContextRunner()
                .withUserConfiguration(SemanticAnalysisConfiguration.class)
                .withBean(JavaSemanticService.class, () -> mock(JavaSemanticService.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DirectCallRelationshipResolver.class);
                    assertThat(context).hasSingleBean(SemanticCallGraphBuilder.class);
                    assertThat(context).hasSingleBean(IncomingSemanticCallGraphBuilder.class);
                });
    }
}
