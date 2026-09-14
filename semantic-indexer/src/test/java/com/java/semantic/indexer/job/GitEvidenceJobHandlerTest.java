package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitEvidenceJobHandlerTest {
    @Test
    void clones_a_configured_remote_before_the_first_refs_preparation() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", Path.of("target/orders"),
                "file:///target/orders.git", "main");
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitRepositoryPort git = mock(GitRepositoryPort.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        GitCatalogManifest manifest = new GitCatalogManifest(GitEvidenceId.create(), repositoryId, Instant.parse("2026-09-15T00:00:00Z"),
                GitEvidenceState.PREPARING, GitCatalogManifest.VERSION);
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
}
