package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaMaintenanceRequiredException;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitEvidenceJobHandlerTest {
    @Test
    void clones_a_configured_remote_before_the_first_refs_preparation(@TempDir Path temporaryDirectory) throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path root = Files.createDirectories(temporaryDirectory.resolve("repos")).resolve("orders");
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", root,
                "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        GitCatalogManifest manifest = new GitCatalogManifest(GitEvidenceId.create(), repositoryId, Instant.parse("2026-09-15T00:00:00Z"),
                GitEvidenceState.PREPARING, GitCatalogManifest.VERSION, GitEvidenceOwnership.standalone());
        when(repositories.get(repositoryId)).thenReturn(runtime);
        when(git.isCloned(runtime.workingTree())).thenReturn(false);
        when(evidence.beginCatalog(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(manifest);
        when(git.fetchRemoteBranches(runtime.workingTree(), runtime.remoteUrl())).thenReturn(List.of());

        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        new GitEvidenceJobHandler(repositories, git, evidence, listener).prepare(job);

        InOrder order = inOrder(git, evidence, listener);
        order.verify(evidence).verifySchemaBeforeEvidence();
        order.verify(listener).beforeMutation(repositoryId);
        order.verify(git).isCloned(runtime.workingTree());
        order.verify(git).clone(runtime.workingTree(), runtime.remoteUrl());
        order.verify(evidence).beginCatalog(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(Instant.class));
        order.verify(git).fetchRemoteBranches(runtime.workingTree(), runtime.remoteUrl());
        order.verify(evidence).appendBranches(manifest, List.of());
    }

    @Test
    void symlinked_configured_checkout_fails_refs_before_git_or_catalog_publication(@TempDir Path temporaryDirectory) throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path parent = Files.createDirectories(temporaryDirectory.resolve("repos"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path root = parent.resolve("orders");
        Files.createSymbolicLink(root, outside);
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", root, "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        when(repositories.get(repositoryId)).thenReturn(runtime);

        Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                mock(RepositoryMutationListener.class)).prepare(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);

        verify(evidence).verifySchemaBeforeEvidence();
        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(evidence, never()).publishComparison(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void symlinked_configured_checkout_fails_review_before_git_or_comparison_publication(@TempDir Path temporaryDirectory) throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path parent = Files.createDirectories(temporaryDirectory.resolve("repos"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path root = parent.resolve("orders");
        Files.createSymbolicLink(root, outside);
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", root, "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = reviewJob(repositoryId);
        when(repositories.get(repositoryId)).thenReturn(runtime);

        Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                mock(RepositoryMutationListener.class)).prepareReview(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);

        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(evidence, never()).publishComparison(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void does_not_attempt_manifest_cleanup_when_schema_verification_rejects_the_job() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", Path.of("target/orders"),
                "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        when(repositories.get(repositoryId)).thenReturn(runtime);
        org.mockito.Mockito.doThrow(new IndexSchemaMaintenanceRequiredException("missing schema"))
                .when(evidence).verifySchemaBeforeEvidence();

        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                listener).prepare(job))
                .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);

        verify(evidence, never()).fail(job);
        verify(git, never()).isCloned(runtime.workingTree());
        verifyNoInteractions(listener);
    }
    @Test
    void failed_invalidation_refuses_standalone_git_and_fails_active_job_without_publication(@TempDir Path temporaryDirectory)
            throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders",
                Files.createDirectories(temporaryDirectory.resolve("repos")).resolve("orders"),
                "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        RepositoryMutationException failure =
                new RepositoryMutationException("workspace still active");
        when(repositories.get(repositoryId)).thenReturn(runtime);
        doThrow(failure).when(listener).beforeMutation(repositoryId);

        Assertions.assertThatThrownBy(() ->
                new GitEvidenceJobHandler(repositories, git, evidence, listener).prepare(job)).isSameAs(failure);

        InOrder order = inOrder(evidence, listener);
        order.verify(evidence).verifySchemaBeforeEvidence();
        order.verify(listener).beforeMutation(repositoryId);
        order.verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(evidence, never()).publishComparison(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }


    @Test
    void failed_review_invalidation_refuses_git_and_fails_job_without_publication(@TempDir Path temporaryDirectory)
            throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders",
                Files.createDirectories(temporaryDirectory.resolve("repos")).resolve("orders"),
                "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        IndexJob job = reviewJob(repositoryId);
        RepositoryMutationException failure =
                new RepositoryMutationException("workspace still active");
        when(repositories.get(repositoryId)).thenReturn(runtime);
        doThrow(failure).when(listener).beforeMutation(repositoryId);

        Assertions.assertThatThrownBy(() ->
                new GitEvidenceJobHandler(repositories, git, evidence, listener).prepareReview(job)).isSameAs(failure);
        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).publishComparison(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void standalone_git_rechecks_the_checkout_after_invalidation(@TempDir Path temporaryDirectory) throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path parent = Files.createDirectories(temporaryDirectory.resolve("repos"));
        Path root = parent.resolve("orders");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", root, "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        when(repositories.get(repositoryId)).thenReturn(runtime);
        doAnswer(invocation -> {
            Files.createSymbolicLink(root, outside);
            return null;
        }).when(listener).beforeMutation(repositoryId);

        Assertions.assertThatThrownBy(() ->
                new GitEvidenceJobHandler(repositories, git, evidence, listener).prepare(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);
        verify(listener).beforeMutation(repositoryId);
        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void review_git_rechecks_the_checkout_after_invalidation(@TempDir Path temporaryDirectory) throws IOException {
        RepositoryId repositoryId = RepositoryId.of("orders");
        Path parent = Files.createDirectories(temporaryDirectory.resolve("repos"));
        Path root = parent.resolve("orders");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", root, "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        RepositoryMutationListener listener = mock(RepositoryMutationListener.class);
        IndexJob job = reviewJob(repositoryId);
        when(repositories.get(repositoryId)).thenReturn(runtime);
        doAnswer(invocation -> {
            Files.createSymbolicLink(root, outside);
            return null;
        }).when(listener).beforeMutation(repositoryId);

        Assertions.assertThatThrownBy(() ->
                new GitEvidenceJobHandler(repositories, git, evidence, listener).prepareReview(job))
                .isInstanceOf(RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);
        verify(listener).beforeMutation(repositoryId);
        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).publishComparison(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    private static IndexJob reviewJob(RepositoryId repositoryId) {
        RepositoryRevision previous = new RepositoryRevision("a".repeat(40));
        RepositoryRevision current = new RepositoryRevision("b".repeat(40));
        ReviewJobPayload payload = new ReviewJobPayload(new ReviewId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                ReviewSelection.range(previous, current),
                Optional.of(new ResolvedReviewEndpoints(Optional.of(previous), current, ReviewBaselineRule.DIRECT_RANGE)),
                Optional.of(new ReviewBuildTargets(Optional.of(new IndexJobTarget(previous, new GenerationId("review-before"), 2L)),
                        new IndexJobTarget(current, new GenerationId("review-after"), 3L))),
                ReviewPreparationStage.PREPARING_GIT, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.REVIEW, Optional.empty(), Optional.of(payload));
    }
}
