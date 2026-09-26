package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JGitEvidenceHistoryAdapterTest {
    @TempDir
    private Path repositoryRoot;

    @Test
    void streams_exact_reachable_commits_with_full_parent_shas_in_stable_order() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryRoot);
        try (Git git = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            Files.writeString(repositoryRoot.resolve("Evidence.java"), "class Evidence { }");
            git.add().addFilepattern(".").call();
            String first = git.commit().setMessage("first").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();
            Files.writeString(repositoryRoot.resolve("Evidence.java"), "class Evidence { int version; }");
            git.add().addFilepattern(".").call();
            String head = git.commit().setMessage("second").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(
                    mock(RepositoryProperties.class), JdtLsTestProperties.linuxUid());
            List<com.java.semantic.model.git.GitCommit> commits = new ArrayList<>();
            adapter.streamReachableHistory(repositoryRoot, RepositoryRevision.ofSha(head), commits::add);

            assertThat(commits).extracting(commit -> commit.revision().value()).containsExactly(head, first);
            assertThat(commits.getFirst().parents()).extracting(RepositoryRevision::value).containsExactly(first);
            assertThat(commits.get(1).parents()).isEmpty();
        }
    }

    @Test
    void refreshes_the_branch_catalog_after_a_remote_branch_is_deleted() throws Exception {
        Path remoteRoot = repositoryRoot.resolve("remote.git");
        Path seedRoot = repositoryRoot.resolve("seed");
        Path checkoutRoot = repositoryRoot.resolve("checkout");
        try (Git remote = Git.init().setBare(true).setDirectory(remoteRoot.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedRoot.toFile()).call()) {
            Files.writeString(seedRoot.resolve("Evidence.java"), "class Evidence { }");
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call();
            seed.remoteAdd().setName("origin").setUri(new URIish(remoteRoot.toUri().toString())).call();
            pushBranch(seed, "main");
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            seed.branchCreate().setName("retired").call();
            pushBranch(seed, "retired");

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(
                    new RepositoryProperties(), JdtLsTestProperties.linuxUid());
            adapter.clone(checkoutRoot, remoteRoot.toUri().toString());

            assertThat(adapter.fetchRemoteBranches(checkoutRoot, remoteRoot.toUri().toString())).extracting(GitBranch::name)
                    .containsExactly("main", "retired");

            seed.push().setRemote("origin").setRefSpecs(new RefSpec(":refs/heads/retired")).call();

            assertThat(adapter.fetchRemoteBranches(checkoutRoot, remoteRoot.toUri().toString())).extracting(GitBranch::name)
                    .containsExactly("main");
        }
    }

    private static void pushBranch(Git git, String branch) throws Exception {
        git.push().setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                .call();
    }
}
