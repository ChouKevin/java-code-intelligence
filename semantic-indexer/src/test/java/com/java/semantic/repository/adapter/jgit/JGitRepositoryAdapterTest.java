package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.repository.RepositoryRevision;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JGitRepositoryAdapterTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void should_clone_sync_and_checkout_revisions_when_local_remote_is_used() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            Path clone = tempDirectory.resolve("clone");

            RepositoryRevision initial = adapter.clone(
                    clone, fixture.remote().toUri().toString(), "main");
            assertThat(initial.value()).hasSize(40);
            assertThat(adapter.currentBranch(clone)).isEqualTo("main");

            String taggedSha = commit(fixture.seed(), fixture.seedRoot(), "second");
            pushBranch(fixture.seed(), "main");
            fixture.seed().tag().setName("v1").call();
            fixture.seed().push().setRemote("origin").setPushTags().call();

            fixture.seed().checkout().setCreateBranch(true).setName("feature").call();
            String featureSha = commit(fixture.seed(), fixture.seedRoot(), "feature");
            pushBranch(fixture.seed(), "feature");

            RepositoryRevision synced = adapter.fetchAndReset(clone, "main");
            assertThat(synced.value()).isEqualTo(taggedSha);

            RepositoryRevision feature = adapter.checkout(clone, "feature");
            assertThat(feature.value()).isEqualTo(featureSha);
            try (Git clonedGit = Git.open(clone.toFile())) {
                StoredConfig config = clonedGit.getRepository().getConfig();
                assertThat(config.getString("branch", "feature", "remote")).isEqualTo("origin");
                assertThat(config.getString("branch", "feature", "merge"))
                        .isEqualTo("refs/heads/feature");
            }

            assertThat(adapter.checkout(clone, "v1").value()).isEqualTo(taggedSha);
            assertThat(adapter.checkout(clone, featureSha).value()).isEqualTo(featureSha);
        }
    }

    @Test
    void should_sync_the_requested_branch_when_current_branch_is_a_distinct_feature() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            String mainSha = commit(fixture.seed(), fixture.seedRoot(), "main");
            pushBranch(fixture.seed(), "main");
            fixture.seed().checkout().setCreateBranch(true).setName("feature").call();
            String featureSha = commit(fixture.seed(), fixture.seedRoot(), "feature");
            pushBranch(fixture.seed(), "feature");
            assertThat(featureSha).isNotEqualTo(mainSha);

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            Path clone = tempDirectory.resolve("cross-branch-clone");
            adapter.clone(clone, fixture.remote().toUri().toString(), "main");
            adapter.checkout(clone, "feature");

            try (Git clonedGit = Git.open(clone.toFile())) {
                StoredConfig config = clonedGit.getRepository().getConfig();
                assertThat(config.getString("branch", "feature", "remote")).isEqualTo("origin");
                assertThat(config.getString("branch", "feature", "merge"))
                        .isEqualTo("refs/heads/feature");
            }

            RepositoryRevision synced = adapter.fetchAndReset(clone, "main");

            assertThat(synced.value()).isEqualTo(mainSha);
            assertThat(adapter.currentBranch(clone)).isEqualTo("main");
            try (Git clonedGit = Git.open(clone.toFile())) {
                assertThat(clonedGit.getRepository().resolve("refs/heads/main").getName())
                        .isEqualTo(mainSha);
                assertThat(clonedGit.getRepository().resolve("refs/heads/feature").getName())
                        .isEqualTo(featureSha);
            }
        }
    }

    @Test
    void should_wrap_as_repository_mutation_exception_when_jgit_throws_an_unchecked_failure()
            throws Exception {
        JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
        Path occupied = tempDirectory.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("existing.txt"), "keep-me");

        assertThatThrownBy(() -> adapter.clone(occupied, "https://example.invalid/repo.git", "main"))
                .isInstanceOf(RepositoryMutationException.class)
                .cause()
                .isInstanceOf(JGitInternalException.class);
    }

    private RemoteFixture createRemote() throws Exception {
        Path remote = tempDirectory.resolve("remote.git");
        try (Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            assertThat(bare.getRepository().isBare()).isTrue();
        }
        Path seedRoot = tempDirectory.resolve("seed");
        Git seed = Git.init()
                .setInitialBranch("main")
                .setDirectory(seedRoot.toFile())
                .call();
        commit(seed, seedRoot, "initial");
        seed.remoteAdd()
                .setName("origin")
                .setUri(new URIish(remote.toUri().toString()))
                .call();
        pushBranch(seed, "main");
        return new RemoteFixture(remote, seedRoot, seed);
    }

    private String commit(Git git, Path root, String content) throws Exception {
        Files.writeString(root.resolve("sample.txt"), content);
        git.add().addFilepattern("sample.txt").call();
        return git.commit()
                .setMessage(content)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .getId()
                .getName();
    }

    private void pushBranch(Git git, String branch) throws Exception {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec(
                        "refs/heads/" + branch + ":refs/heads/" + branch))
                .call();
    }

    private record RemoteFixture(Path remote, Path seedRoot, Git seed) implements AutoCloseable {

        @Override
        public void close() {
            seed.close();
        }
    }
}
