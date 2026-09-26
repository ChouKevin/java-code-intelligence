package com.java.semantic.indexer.repository;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExactRepositoryCheckoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void checks_out_the_admitted_revision_after_the_default_branch_moves() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRevision movedBranchRevision = RepositoryRevision.ofSha("b".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Files.createDirectories(root.getParent());
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.resolveRemoteRef("https://example.test/orders.git", "main")).thenReturn(movedBranchRevision);
        when(git.currentRevision(root)).thenReturn(admittedRevision);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git, listener);

        IndexBuildService.CheckedOutRepository result = checkout.checkout(job(repositoryId, admittedRevision));
        org.mockito.InOrder order = inOrder(listener, git);
        order.verify(listener).beforeMutation(repositoryId);
        order.verify(git).isCloned(root);

        assertThat(result.root()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(result.revision()).isEqualTo(admittedRevision);
        verify(git).fetch(root, registry.get(repositoryId).remoteUrl());
        verify(git).checkoutDetached(root, admittedRevision);
        verify(git, never()).resolveRemoteRef(anyString(), anyString());
    }

    @Test
    void clones_when_the_repository_working_tree_is_absent() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Files.createDirectories(root.getParent());
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(false);
        when(git.currentRevision(root)).thenReturn(admittedRevision);
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class));

        checkout.checkout(job(repositoryId, admittedRevision));

        verify(git).clone(root, "https://example.test/orders.git");
        verify(git, never()).fetch(root, "https://example.test/orders.git");
        verify(git).checkoutDetached(root, admittedRevision);
    }

    @Test
    void rejects_the_checkout_when_head_does_not_match_the_admitted_revision() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision admittedRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Files.createDirectories(root.getParent());
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.currentRevision(root)).thenReturn(RepositoryRevision.ofSha("b".repeat(40)));
        ExactRepositoryCheckout checkout = new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class));

        assertThatThrownBy(() -> checkout.checkout(job(repositoryId, admittedRevision)))
                .hasMessageContaining("checked out revision differs");
    }

    @Test
    void rejects_missing_managed_parent_without_creating_it_or_calling_git() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        GitRepositoryPort git = mock(GitRepositoryPort.class);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(root.getParent()).doesNotExist();
        assertThat(root).doesNotExist();
        org.mockito.Mockito.verifyNoInteractions(git);
    }

    @Test
    void rejects_non_directory_managed_parent_without_calling_git() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Files.writeString(root.getParent(), "occupied");
        GitRepositoryPort git = mock(GitRepositoryPort.class);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(root.getParent()).hasContent("occupied");
        org.mockito.Mockito.verifyNoInteractions(git);
    }

    @Test
    void rejects_symlinked_checkout_before_git_mutates_outside_tree() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.createDirectories(root.getParent());
        Files.createSymbolicLink(root, outside);
        GitRepositoryPort git = mock(GitRepositoryPort.class);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(git);
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void rejects_symlink_above_configured_parent_before_git_mutates_outside_tree() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path link = temporaryDirectory.resolve("alias");
        Files.createSymbolicLink(link, outside);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(link.resolve("repos").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/orders.git");
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), config));
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        GitRepositoryPort git = mock(GitRepositoryPort.class);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(git);
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void allows_worktree_symlinks_during_existing_checkout_preflight() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = Files.createDirectories(registry.get(repositoryId).workingTree());
        Path target = root.resolve("source-target.java");
        Files.writeString(target, "class SourceTarget {}");
        Files.createSymbolicLink(root.resolve("source-link.java"), target.getFileName());
        RepositoryRevision revision = RepositoryRevision.ofSha("a".repeat(40));
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.currentRevision(root)).thenReturn(revision);

        assertThat(new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, revision)).root())
                .isEqualTo(root);
        verify(git).fetch(root, registry.get(repositoryId).remoteUrl());
    }

    @Test
    void accepts_existing_contained_checkout_tree() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = Files.createDirectories(registry.get(repositoryId).workingTree());
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        RepositoryRevision revision = RepositoryRevision.ofSha("a".repeat(40));
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        when(git.isCloned(root)).thenReturn(true);
        when(git.currentRevision(root)).thenReturn(revision);

        assertThat(new ExactRepositoryCheckout(registry, git, mock(RepositoryMutationListener.class))
                .checkout(job(repositoryId, revision)).root())
                .isEqualTo(root);
        verify(git).fetch(root, registry.get(repositoryId).remoteUrl());
    }

    @Test
    void refuses_all_git_when_workspace_invalidation_fails() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Files.createDirectories(registry.get(repositoryId).workingTree().getParent());
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        com.java.semantic.repository.application.RepositoryMutationException failure =
                new com.java.semantic.repository.application.RepositoryMutationException("workspace still active");
        doThrow(failure).when(listener).beforeMutation(repositoryId);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, listener)
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isSameAs(failure);
        verifyNoInteractions(git);
    }

    @Test
    void rechecks_the_managed_checkout_after_workspace_invalidation() throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntimeRegistry registry = registry(repositoryId);
        Path root = registry.get(repositoryId).workingTree();
        Files.createDirectories(root.getParent());
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        doAnswer(invocation -> {
            Files.createSymbolicLink(root, outside);
            return null;
        }).when(listener).beforeMutation(repositoryId);

        assertThatThrownBy(() -> new ExactRepositoryCheckout(registry, git, listener)
                .checkout(job(repositoryId, RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(IllegalStateException.class);
        verify(listener).beforeMutation(repositoryId);
        verifyNoInteractions(git);
        assertThat(outside).isEmptyDirectory();
    }

    private RepositoryRuntimeRegistry registry(RepositoryId repositoryId) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("repos").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/orders.git");
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), config));
        return new RepositoryRuntimeRegistry(properties);
    }

    private static IndexJob job(RepositoryId repositoryId, RepositoryRevision revision) {
        return new IndexJob(new IndexJobId("job-1"), repositoryId,
                Optional.of(new com.java.semantic.indexer.job.IndexJobTarget(revision, new GenerationId("generation-1"), 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, com.java.semantic.indexer.job.IndexJobOperation.BUILD);
    }
}
