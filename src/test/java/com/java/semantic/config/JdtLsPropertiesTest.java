package com.java.semantic.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Duration;

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
        assertThat(properties.getMaxHeap()).isEqualTo("3g");
    }
}
