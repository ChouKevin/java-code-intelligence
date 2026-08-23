package com.java.semantic.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** source-symbol context candidate limit 的 default 與最小值契約 */
class SourceSymbolResolutionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_default_to_one_hundred() {
        contextRunner.run(context -> assertThat(context.getBean(SourceSymbolResolutionProperties.class)
                .contextCandidateLimit()).isEqualTo(100));
    }

    @Test
    void should_reject_limit_below_two() {
        contextRunner
                .withPropertyValues("semantic.discovery.source-symbol-resolution.context-candidate-limit=1")
                .run(context -> assertThat(context).hasFailed());
    }
}
