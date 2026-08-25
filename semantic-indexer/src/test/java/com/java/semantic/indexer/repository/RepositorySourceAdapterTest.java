package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        RepositorySourceAdapter source = new RepositorySourceAdapter(registry, git);

        assertThat(source.ensure(repositoryId)).isEqualTo(main);
        assertThat(source.sync(repositoryId, Optional.of("release"))).isEqualTo(release);
        assertThat(registry.get(repositoryId).snapshot()).isEmpty();
        verify(git).resolveRemoteRef("https://example.test/orders.git", "main");
        verify(git).resolveRemoteRef("https://example.test/orders.git", "release");
        verifyNoMoreInteractions(git);
    }

    @Test
    void rejects_a_well_formed_checkout_revision_when_git_cannot_resolve_it() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        String missingRevision = "c".repeat(40);
        when(git.resolveRemoteRef("https://example.test/orders.git", missingRevision))
                .thenThrow(new IllegalArgumentException("revision was not found"));
        RepositorySourceAdapter source = new RepositorySourceAdapter(remoteRegistry(repositoryId), git);

        assertThatThrownBy(() -> source.checkout(repositoryId, missingRevision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision was not found");
    }

    @Test
    void rejects_uppercase_and_abbreviated_checkout_revisions_before_remote_resolution() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        RepositorySourceAdapter source = new RepositorySourceAdapter(remoteRegistry(repositoryId), git);

        assertThatThrownBy(() -> source.checkout(repositoryId, "A".repeat(40)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase hexadecimal");
        assertThatThrownBy(() -> source.checkout(repositoryId, "a".repeat(39)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40-character lowercase hexadecimal");
        verifyNoInteractions(git);
    }

    private RepositoryRuntimeRegistry remoteRegistry(RepositoryId repositoryId) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("repos").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/orders.git");
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), config));
        return new RepositoryRuntimeRegistry(properties);
    }
}
