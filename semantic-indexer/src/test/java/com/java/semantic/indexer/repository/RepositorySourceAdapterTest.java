package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositorySourceAdapterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolves_remote_default_and_branch_without_mutating_the_runtime_working_tree() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = remoteRegistry(repositoryId);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        RepositoryRevision main = new RepositoryRevision("a".repeat(40));
        RepositoryRevision release = new RepositoryRevision("b".repeat(40));
        when(git.resolveRemoteRef("https://example.test/orders.git", "main")).thenReturn(main);
        when(git.resolveRemoteRef("https://example.test/orders.git", "release")).thenReturn(release);
        RepositorySourceAdapter source = new RepositorySourceAdapter(
                registry, git, new FixtureRevisionResolver());

        assertThat(source.ensure(repositoryId)).isEqualTo(main);
        assertThat(source.sync(repositoryId, Optional.of("release"))).isEqualTo(release);
        assertThat(registry.get(repositoryId).snapshot()).isEmpty();
    }

    @Test
    void rejects_a_well_formed_checkout_revision_when_git_cannot_resolve_it() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        String missingRevision = "c".repeat(40);
        when(git.resolveRemoteRef("https://example.test/orders.git", missingRevision))
                .thenThrow(new IllegalArgumentException("revision was not found"));
        RepositorySourceAdapter source = new RepositorySourceAdapter(
                remoteRegistry(repositoryId), git, new FixtureRevisionResolver());

        assertThatThrownBy(() -> source.checkout(repositoryId, missingRevision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision was not found");
    }

    private RepositoryRuntimeRegistry remoteRegistry(RepositoryId repositoryId) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("repos").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setMode(RepositoryMode.REMOTE);
        config.setUrl("https://example.test/orders.git");
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), config));
        return new RepositoryRuntimeRegistry(properties);
    }
}
