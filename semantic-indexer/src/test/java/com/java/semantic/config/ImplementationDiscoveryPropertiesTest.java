package com.java.semantic.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementationDiscoveryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_use_default_candidate_limit() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ImplementationDiscoveryProperties.class);
            assertThat(context.getBean(ImplementationDiscoveryProperties.class).candidateLimit())
                    .isEqualTo(100);
        });
    }

    @Test
    void should_bind_custom_candidate_limit() {
        contextRunner
                .withPropertyValues("semantic.discovery.method-implementations.candidate-limit=250")
                .run(context -> assertThat(context.getBean(ImplementationDiscoveryProperties.class).candidateLimit())
                        .isEqualTo(250));
    }

    @ParameterizedTest
    @MethodSource("acceptedCandidateLimits")
    void should_accept_candidate_limit_boundaries(int candidateLimit) {
        contextRunner
                .withPropertyValues("semantic.discovery.method-implementations.candidate-limit=" + candidateLimit)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @MethodSource("rejectedCandidateLimits")
    void should_reject_candidate_limit_outside_supported_range(int candidateLimit) {
        contextRunner
                .withPropertyValues("semantic.discovery.method-implementations.candidate-limit=" + candidateLimit)
                .run(context -> assertThat(context).hasFailed());
    }

    private static Stream<Arguments> acceptedCandidateLimits() {
        return Stream.of(Arguments.of(1), Arguments.of(1000));
    }

    private static Stream<Arguments> rejectedCandidateLimits() {
        return Stream.of(Arguments.of(0), Arguments.of(1001));
    }
}
