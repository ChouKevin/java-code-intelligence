package com.java.semantic.repository.config;

import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.RecordComponent;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void should_reject_a_blank_git_url_when_constructing_the_runtime_registry() {
        RepositoryProperties properties = propertiesWithRepository();
        properties.getRepositories().get("test-repo").setUrl("   ");

        assertThatThrownBy(() -> new RepositoryRuntimeRegistry(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void should_reject_an_explicitly_blank_default_branch_when_constructing_the_runtime_registry() {
        RepositoryProperties properties = propertiesWithRepository();
        properties.getRepositories().get("test-repo").setDefaultBranch(" \t");

        assertThatThrownBy(() -> new RepositoryRuntimeRegistry(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default branch");
    }

    @Test
    void should_reject_fixture_only_binding_when_constructing_the_runtime_registry() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("semantic.repositories.test-repo.display-name", "Fixture repo")
                .withProperty("semantic.repositories.test-repo.mode", String.join("_", "LOCAL", "FIXTURE"))
                .withProperty("semantic.repositories.test-repo.path", "/tmp/fixture");
        RepositoryProperties properties = Binder.get(environment)
                .bind("semantic", Bindable.of(RepositoryProperties.class))
                .orElseThrow(() -> new IllegalStateException("semantic properties are required"));

        assertThatThrownBy(() -> new RepositoryRuntimeRegistry(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void should_keep_main_as_the_default_branch_when_binding_omits_it() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("semantic.repositories.test-repo.url", "https://example.test/test-repo.git");
        RepositoryProperties properties = Binder.get(environment)
                .bind("semantic", Bindable.of(RepositoryProperties.class))
                .orElseThrow(() -> new IllegalStateException("semantic properties are required"));

        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);

        assertThat(registry.all()).singleElement()
                .extracting(runtime -> runtime.defaultBranch())
                .isEqualTo("main");
    }

    @Test
    void should_not_expose_the_remote_url_in_the_repository_status_shape() {
        assertThat(RepositoryStatus.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("remoteUrl");
    }

    private static RepositoryProperties propertiesWithRepository() {
        RepositoryProperties properties = new RepositoryProperties();
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/test-repo.git");
        properties.getRepositories().put("test-repo", config);
        return properties;
    }
}
