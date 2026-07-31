package com.java.semantic.config;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 驗證 exact content 傳輸界限的組態綁定 */
class ExactContentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SemanticAnalysisConfiguration.class);

    @Test
    void should_use_32_kib_defaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ExactContentProperties.class);
            ExactContentProperties properties = context.getBean(ExactContentProperties.class);

            assertThat(properties.inlineUtf8Bytes()).isEqualTo(32768);
            assertThat(properties.segmentUtf8Bytes()).isEqualTo(32768);
        });
    }

    @ParameterizedTest
    @MethodSource("acceptedByteLimits")
    void should_accept_configured_byte_limit_boundaries(int byteLimit) {
        contextRunner.withPropertyValues(
                        "semantic.discovery.exact-content.inline-utf8-bytes=" + byteLimit,
                        "semantic.discovery.exact-content.segment-utf8-bytes=" + byteLimit)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @MethodSource("rejectedByteLimits")
    void should_reject_configured_byte_limits_outside_the_supported_range(int byteLimit) {
        contextRunner.withPropertyValues(
                        "semantic.discovery.exact-content.inline-utf8-bytes=" + byteLimit,
                        "semantic.discovery.exact-content.segment-utf8-bytes=" + byteLimit)
                .run(context -> assertThat(context).hasFailed());
    }

    private static Stream<Arguments> acceptedByteLimits() {
        return Stream.of(Arguments.of(1024), Arguments.of(65536));
    }

    private static Stream<Arguments> rejectedByteLimits() {
        return Stream.of(Arguments.of(1023), Arguments.of(65537));
    }
}
