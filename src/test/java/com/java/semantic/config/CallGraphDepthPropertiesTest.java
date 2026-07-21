package com.java.semantic.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphDepthPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_default_call_graph_depth_to_three() {
        contextRunner.run(context -> assertThat(context.getBean(CallGraphDepthProperties.class).callGraphDepth())
                .isEqualTo(3));
    }

    @Test
    void should_bind_call_graph_depth_from_entry_point_prefix() {
        contextRunner
                .withPropertyValues("entry-point.call-graph-depth=7")
                .run(context -> assertThat(context.getBean(CallGraphDepthProperties.class).callGraphDepth())
                        .isEqualTo(7));
    }

    @Test
    void should_reject_non_positive_call_graph_depth() {
        for (int invalidDepth : new int[]{0, -1}) {
            contextRunner
                    .withPropertyValues("entry-point.call-graph-depth=" + invalidDepth)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
