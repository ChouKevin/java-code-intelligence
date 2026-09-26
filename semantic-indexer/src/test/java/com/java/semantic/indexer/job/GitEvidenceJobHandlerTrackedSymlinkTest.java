package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.support.JdtLsTestProperties;

import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.repository.port.RepositoryMutationListener;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitEvidenceJobHandlerTrackedSymlinkTest {
    @Test
    void publishes_remote_refs_for_an_existing_checkout_with_a_tracked_symlink(@TempDir Path temporaryDirectory)
            throws Exception {
        Path remotePath = temporaryDirectory.resolve("remote.git");
        Path seedRoot = temporaryDirectory.resolve("seed");
        RepositoryRevision head;
        try (Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedRoot.toFile()).call()) {
            Files.writeString(seedRoot.resolve("target.txt"), "tracked target");
            Files.createSymbolicLink(seedRoot.resolve("tracked-link"), Path.of("target.txt"));
            seed.add().addFilepattern(".").call();
            head = RepositoryRevision.ofSha(seed.commit()
                    .setMessage("track a symlink")
                    .setAuthor("Test", "test@example.test")
                    .setCommitter("Test", "test@example.test")
                    .call()
                    .getId()
                    .getName());
            seed.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(remotePath.toUri().toString()))
                    .call();
            seed.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                    .call();
            remote.getRepository().updateRef(Constants.HEAD, true).link("refs/heads/main");
        }

        RepositoryId repositoryId = RepositoryId.of("orders");
        Path managedParent = Files.createDirectories(temporaryDirectory.resolve("repos"));
        Path checkoutRoot = managedParent.resolve("orders");
        GitRepositoryPort git = new JGitRepositoryAdapter(
                new RepositoryProperties(), JdtLsTestProperties.linuxUid());
        git.clone(checkoutRoot, remotePath.toUri().toString());
        assertThat(Files.isSymbolicLink(checkoutRoot.resolve("tracked-link"))).isTrue();

        RepositoryRuntime runtime = new RepositoryRuntime(repositoryId, "Orders", checkoutRoot,
                remotePath.toUri().toString(), "main", managedParent);
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        GitEvidencePublicationStore evidence = mock(GitEvidencePublicationStore.class);
        IndexJob job = new IndexJob(IndexJobId.create(), repositoryId, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_REFS, Optional.of(GitEvidenceJob.refs()));
        GitCatalogManifest catalog = new GitCatalogManifest(GitEvidenceId.create(), repositoryId,
                Instant.parse("2026-09-15T00:00:00Z"), GitEvidenceState.PREPARING, GitCatalogManifest.VERSION,
                GitEvidenceOwnership.standalone());
        when(repositories.get(repositoryId)).thenReturn(runtime);
        when(evidence.beginCatalog(eq(job), any(Instant.class))).thenReturn(catalog);

        assertThatCode(() -> new GitEvidenceJobHandler(repositories, git, evidence,
                mock(RepositoryMutationListener.class)).prepare(job))
                .doesNotThrowAnyException();

        verify(evidence).appendBranches(catalog, List.of(new GitBranch("main", head)));
    }
}
