package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.config.RepositoryProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Ref;
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
    void should_fetch_and_checkout_an_exact_revision_after_main_moves() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            Path clone = tempDirectory.resolve("clone");
            RepositoryRevision admittedRevision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());

            RepositoryRevision clonedRevision = adapter.clone(clone, fixture.remote().toUri().toString());
            assertThat(clonedRevision).isEqualTo(admittedRevision);
            String movedBranchRevision = commit(fixture.seed(), fixture.seedRoot(), "new-main-tip");
            pushBranch(fixture.seed(), "main");
            adapter.fetch(clone);
            adapter.checkoutDetached(clone, admittedRevision);

            assertThat(adapter.currentRevision(clone)).isEqualTo(admittedRevision);
            try (Git checkedOut = Git.open(clone.toFile())) {
                Ref head = checkedOut.getRepository().exactRef("HEAD");
                assertThat(head.isSymbolic()).isFalse();
                assertThat(checkedOut.getRepository().resolve("refs/remotes/origin/main").getName())
                        .isEqualTo(movedBranchRevision);
            }
        }
    }

    @Test
    void should_detach_head_when_a_local_branch_has_the_same_name_as_the_exact_revision() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            Path clone = tempDirectory.resolve("clone-with-sha-branch");
            RepositoryRevision revision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());

            adapter.clone(clone, fixture.remote().toUri().toString());
            try (Git local = Git.open(clone.toFile())) {
                local.branchCreate().setName(revision.value()).setStartPoint(revision.value()).call();
            }

            adapter.checkoutDetached(clone, revision);

            try (Git checkedOut = Git.open(clone.toFile())) {
                Ref head = checkedOut.getRepository().exactRef("HEAD");
                assertThat(head.isSymbolic()).isFalse();
                assertThat(head.getObjectId().getName()).isEqualTo(revision.value());
            }
        }
    }

    @Test
    void should_remove_untracked_and_ignored_source_artifacts_when_checking_out_an_exact_revision()
            throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            Files.writeString(fixture.seedRoot().resolve(".gitignore"), "ignored/\n");
            fixture.seed().add().addFilepattern(".gitignore").call();
            fixture.seed().commit()
                    .setMessage("ignore generated sources")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            pushBranch(fixture.seed(), "main");

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            Path clone = tempDirectory.resolve("clone-with-contaminants");
            RepositoryRevision revision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());
            adapter.clone(clone, fixture.remote().toUri().toString());
            Files.writeString(clone.resolve("untracked.java"), "class Untracked {}\n");
            Path ignoredDirectory = clone.resolve("ignored");
            Files.createDirectories(ignoredDirectory);
            Files.writeString(ignoredDirectory.resolve("Ignored.java"), "class Ignored {}\n");

            adapter.checkoutDetached(clone, revision);

            assertThat(Files.exists(clone.resolve("untracked.java"))).isFalse();
            assertThat(Files.exists(ignoredDirectory.resolve("Ignored.java"))).isFalse();
            assertThat(Files.readString(clone.resolve("sample.txt"))).isEqualTo("initial");
        }
    }

    @Test
    void should_wrap_as_repository_mutation_exception_when_jgit_clone_fails()
            throws Exception {
        JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
        Path occupied = tempDirectory.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("existing.txt"), "keep-me");

        assertThatThrownBy(() -> adapter.clone(occupied, "https://example.invalid/repo.git"))
                .isInstanceOf(RepositoryMutationException.class)
                .cause()
                .isInstanceOf(JGitInternalException.class);
    }

    @Test
    void should_resolve_remote_branch_and_reachable_exact_revision_without_a_worktree_checkout()
            throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            String historicalRevision = fixture.seed().getRepository().resolve("refs/heads/main").getName();
            String revision = commit(fixture.seed(), fixture.seedRoot(), "new-main-tip");
            pushBranch(fixture.seed(), "main");
            String sourceBefore = Files.readString(fixture.seedRoot().resolve("sample.txt"));

            assertThat(adapter.resolveRemoteRef(fixture.remote().toUri().toString(), "main").value())
                    .isEqualTo(revision);
            assertThat(adapter.resolveRemoteRef(fixture.remote().toUri().toString(), revision).value())
                    .isEqualTo(revision);
            assertThat(adapter.resolveRemoteRef(fixture.remote().toUri().toString(), historicalRevision).value())
                    .isEqualTo(historicalRevision);
            assertThatThrownBy(() -> adapter.resolveRemoteRef(fixture.remote().toUri().toString(), "f".repeat(40)))
                    .isInstanceOf(RepositoryMutationException.class);
            assertThat(Files.readString(fixture.seedRoot().resolve("sample.txt"))).isEqualTo(sourceBefore);
        }
    }

    @Test
    void should_peel_an_annotated_remote_tag_to_its_reachable_commit() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            String revision = commit(fixture.seed(), fixture.seedRoot(), "tagged");
            fixture.seed().tag().setName("annotated-v1").setAnnotated(true).setMessage("release").call();
            fixture.seed().push().setRemote("origin").setPushTags().call();

            RepositoryRevision resolved = new JGitRepositoryAdapter(new RepositoryProperties())
                    .resolveRemoteRef(fixture.remote().toUri().toString(), "annotated-v1");

            assertThat(resolved.value()).isEqualTo(revision);
        }
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
        try (Git bare = Git.open(remote.toFile())) {
            bare.getRepository().updateRef("HEAD", true).link("refs/heads/main");
        }
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
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                .call();
    }

    private record RemoteFixture(Path remote, Path seedRoot, Git seed) implements AutoCloseable {

        @Override
        public void close() {
            seed.close();
        }
    }
}
