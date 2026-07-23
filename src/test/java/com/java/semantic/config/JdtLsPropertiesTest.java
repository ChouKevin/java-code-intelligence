package com.java.semantic.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JdtLsPropertiesTest {

    @Test
    void should_expose_all_operational_defaults_when_properties_are_absent() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(JdtLsConfiguration.class);

        contextRunner.run(context -> {
            JdtLsProperties properties = context.getBean(JdtLsProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getHome()).isEqualTo(Path.of("/opt/jdtls"));
            assertThat(properties.getWorkspaceDataRoot()).isEqualTo(Path.of("/data/jdtls"));
            assertThat(properties.getStartupTimeout()).isEqualTo(Duration.ofSeconds(180));
            assertThat(properties.getImportTimeout()).isEqualTo(Duration.ofSeconds(900));
            assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getMaxActiveWorkspaces()).isEqualTo(2);
            assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.getMaintenanceInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.getMaxHeap()).isEqualTo("2g");
        });
    }

    @Test
    void should_bind_all_jdtls_settings_when_properties_are_present() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("semantic.jdtls.enabled", "false")
                .withProperty("semantic.jdtls.home", "/tmp/jdtls-home")
                .withProperty("semantic.jdtls.workspace-data-root", "/tmp/jdtls-workspaces")
                .withProperty("semantic.jdtls.startup-timeout", "45s")
                .withProperty("semantic.jdtls.import-timeout", "12m")
                .withProperty("semantic.jdtls.request-timeout", "5s")
                .withProperty("semantic.jdtls.max-active-workspaces", "4")
                .withProperty("semantic.jdtls.idle-timeout", "20m")
                .withProperty("semantic.jdtls.maintenance-interval", "45s")
                .withProperty("semantic.jdtls.max-heap", "3g");

        JdtLsProperties properties = Binder.get(environment)
                .bind("semantic.jdtls", Bindable.of(JdtLsProperties.class))
                .orElseThrow(() -> new IllegalStateException("JDT LS properties are required"));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getHome()).isEqualTo(Path.of("/tmp/jdtls-home"));
        assertThat(properties.getWorkspaceDataRoot()).isEqualTo(Path.of("/tmp/jdtls-workspaces"));
        assertThat(properties.getStartupTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getImportTimeout()).isEqualTo(Duration.ofMinutes(12));
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getMaxActiveWorkspaces()).isEqualTo(4);
        assertThat(properties.getIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.getMaintenanceInterval()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getMaxHeap()).isEqualTo("3g");
    }

    @ParameterizedTest
    @MethodSource("invalidLifecycleProperties")
    void should_fail_context_startup_for_invalid_lifecycle_properties(
            String property,
            String value,
            String expectedMessageFragment) {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(JdtLsConfiguration.class)
                .withPropertyValues(property + "=" + value);

        contextRunner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessageFragment));
    }

    private static Stream<Arguments> invalidLifecycleProperties() {
        return Stream.of(
                Arguments.of("semantic.jdtls.max-active-workspaces", "0", "maxActiveWorkspaces"),
                Arguments.of("semantic.jdtls.idle-timeout", "0s", "idleTimeout"),
                Arguments.of("semantic.jdtls.maintenance-interval", "-1s", "maintenanceInterval"));
    }
}
