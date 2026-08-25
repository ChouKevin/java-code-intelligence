package com.java.semantic.repository.config;

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
    void should_bind_git_url_and_default_branch_without_fixture_source_properties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("semantic.data-root", "/tmp/repos")
                .withProperty("semantic.repository-lock-timeout", "250ms")
                .withProperty("semantic.repositories.test-repo.url", "https://example.test/test-repo.git")
                .withProperty("semantic.repositories.test-repo.default-branch", "release");

        RepositoryProperties properties = Binder.get(environment)
                .bind("semantic", Bindable.of(RepositoryProperties.class))
                .orElseThrow(() -> new IllegalStateException("semantic properties are required"));

        assertThat(properties.getDataRoot()).isEqualTo("/tmp/repos");
        assertThat(properties.getRepositoryLockTimeout()).isEqualTo(Duration.ofMillis(250));
        RepositoryProperties.RepositoryConfig config = properties.getRepositories().get("test-repo");
        assertThat(config.getUrl()).isEqualTo("https://example.test/test-repo.git");
        assertThat(config.getDefaultBranch()).isEqualTo("release");
        assertThat(RepositoryProperties.RepositoryConfig.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("mode", "path");
    }
}
