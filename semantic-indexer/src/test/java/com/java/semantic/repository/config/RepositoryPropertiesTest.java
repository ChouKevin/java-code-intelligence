package com.java.semantic.repository.config;

import com.java.semantic.repository.domain.RepositoryMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryPropertiesTest {

    @Test
    void should_not_expose_the_git_token_when_properties_are_printed() {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setGitToken("ghp_realsecretvalue");

        assertThat(properties.toString()).doesNotContain("ghp_realsecretvalue");
        assertThat(properties.toString()).contains("<set>");
    }

    @Test
    void should_default_to_remote_mode_when_mode_is_absent() {
        assertThat(new RepositoryProperties.RepositoryConfig().getMode())
                .isEqualTo(RepositoryMode.REMOTE);
    }

    @Test
    void should_bind_repository_configuration_when_properties_are_present() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("semantic.data-root", "/tmp/repos")
                .withProperty("semantic.repository-lock-timeout", "250ms")
                .withProperty("semantic.repositories.test-repo.mode", "LOCAL_FIXTURE")
                .withProperty("semantic.repositories.test-repo.path", "/tmp/fixture");

        RepositoryProperties properties = Binder.get(environment)
                .bind("semantic", Bindable.of(RepositoryProperties.class))
                .orElseThrow(() -> new IllegalStateException("semantic properties are required"));

        assertThat(properties.getDataRoot()).isEqualTo("/tmp/repos");
        assertThat(properties.getRepositoryLockTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.getRepositories().get("test-repo").getMode())
                .isEqualTo(RepositoryMode.LOCAL_FIXTURE);
        assertThat(properties.getRepositories().get("test-repo").getPath())
                .isEqualTo("/tmp/fixture");
    }
}
