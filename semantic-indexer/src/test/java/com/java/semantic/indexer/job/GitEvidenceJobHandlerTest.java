package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaMaintenanceRequiredException;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.CapturedReviewBaseline;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
        when(git.fetchRemoteBranches(runtime.workingTree())).thenReturn(List.of());

        new GitEvidenceJobHandler(repositories, git, evidence).prepare(job);

        org.mockito.InOrder order = inOrder(git, evidence);
        order.verify(evidence).verifySchemaBeforeEvidence();
        order.verify(git).clone(runtime.workingTree(), runtime.remoteUrl());
        order.verify(evidence).beginCatalog(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(Instant.class));
        order.verify(git).fetchRemoteBranches(runtime.workingTree());
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

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence).prepare(job))
                .isInstanceOf(com.java.semantic.repository.application.RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);

        verify(evidence).verifySchemaBeforeEvidence();
        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(evidence, never()).publishComparison(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
        RepositoryRevision previous = new RepositoryRevision("a".repeat(40));
        RepositoryRevision current = new RepositoryRevision("b".repeat(40));
        PublishedGenerationPointer pointer = new PublishedGenerationPointer(previous, new GenerationId("baseline"),
                new ManifestDigest("1".repeat(64)), "baseline-job", Instant.parse("2026-09-15T00:00:00Z"));
        ReviewJobPayload payload = new ReviewJobPayload(new ReviewId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                new CapturedReviewBaseline(pointer, Instant.parse("2026-09-15T00:00:00Z")), current,
                new ReviewBuildTargets(new IndexJobTarget(previous, new GenerationId("review-a"), 2L),
                        new IndexJobTarget(current, new GenerationId("review-b"), 3L)),
                ReviewPreparationStage.PREPARING_A, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.REVIEW, Optional.empty(), Optional.of(payload));
        when(repositories.get(repositoryId)).thenReturn(runtime);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence).prepareReview(job))
                .isInstanceOf(com.java.semantic.repository.application.RepositoryMutationException.class)
                .hasCauseInstanceOf(IOException.class);

        verify(evidence).fail(job);
        verifyNoInteractions(git);
        verify(evidence, never()).beginCatalog(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(evidence, never()).publishComparison(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GitEvidenceJobHandler(repositories, git, evidence).prepare(job))
                .isInstanceOf(IndexSchemaMaintenanceRequiredException.class);

        verify(evidence, never()).fail(job);
        verify(git, never()).isCloned(runtime.workingTree());
    }
}
