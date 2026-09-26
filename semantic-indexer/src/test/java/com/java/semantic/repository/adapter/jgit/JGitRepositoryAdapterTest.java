package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JGitRepositoryAdapterTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void should_fetch_and_checkout_an_exact_revision_after_main_moves() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = adapter();
            Path clone = tempDirectory.resolve("clone");
            RepositoryRevision admittedRevision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());

            RepositoryRevision clonedRevision = adapter.clone(clone, fixture.remote().toUri().toString());
            assertThat(clonedRevision).isEqualTo(admittedRevision);
            String movedBranchRevision = commit(fixture.seed(), fixture.seedRoot(), "new-main-tip");
            pushBranch(fixture.seed(), "main");
            adapter.fetch(clone, fixture.remote().toUri().toString());
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
            JGitRepositoryAdapter adapter = adapter();
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
    void restores_the_exact_tracked_tree_and_removes_only_indexer_owned_build_output() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            Files.writeString(fixture.seedRoot().resolve(".gitignore"), "ignored/\n");
            fixture.seed().add().addFilepattern(".gitignore").call();
            fixture.seed().commit()
                    .setMessage("ignore operator files")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call();
            pushBranch(fixture.seed(), "main");

            JGitRepositoryAdapter adapter = adapter();
            Path clone = tempDirectory.resolve("clone-with-build-output");
            RepositoryRevision revision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());
            adapter.clone(clone, fixture.remote().toUri().toString());
            Files.writeString(clone.resolve("sample.txt"), "modified");
            Files.writeString(clone.resolve("untracked.java"), "class Operator {}\n");
            Path ignoredDirectory = clone.resolve("ignored");
            Files.createDirectories(ignoredDirectory);
            Files.writeString(ignoredDirectory.resolve("Ignored.java"), "class Ignored {}\n");
            Files.createDirectories(clone.resolve("target"));
            Files.writeString(clone.resolve("target/Indexer.class"), "generated");
            Files.createDirectories(clone.resolve("build"));
            Files.writeString(clone.resolve("build/Indexer.class"), "generated");
            Files.writeString(clone.resolve(".project"), "indexer import metadata");

            adapter.checkoutDetached(clone, revision);

            assertThat(Files.readString(clone.resolve("sample.txt"))).isEqualTo("initial");
            assertThat(Files.exists(clone.resolve("target/Indexer.class"))).isFalse();
            assertThat(Files.exists(clone.resolve("build/Indexer.class"))).isFalse();
            assertThat(Files.exists(clone.resolve(".project"))).isFalse();
            assertThat(Files.readString(clone.resolve("untracked.java"))).isEqualTo("class Operator {}\n");
            assertThat(Files.readString(ignoredDirectory.resolve("Ignored.java"))).isEqualTo("class Ignored {}\n");
        }
    }

    @Test
    void should_wrap_as_repository_mutation_exception_when_jgit_clone_fails()
            throws Exception {
        JGitRepositoryAdapter adapter = adapter();
        Path occupied = tempDirectory.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("existing.txt"), "keep-me");

        assertThatThrownBy(() -> adapter.clone(occupied, "https://example.invalid/repo.git"))
                .isInstanceOf(RepositoryMutationException.class);
    }

    @Test
    void should_resolve_remote_branch_and_reachable_exact_revision_without_a_worktree_checkout()
            throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = adapter();
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

            RepositoryRevision resolved = adapter()
                    .resolveRemoteRef(fixture.remote().toUri().toString(), "annotated-v1");

            assertThat(resolved.value()).isEqualTo(revision);
        }
    }

    @Test
    void rejects_a_locally_retained_endpoint_after_the_trusted_remote_ref_is_rewritten() throws Exception {
        try (RemoteFixture fixture = createRemote()) {
            JGitRepositoryAdapter adapter = adapter();
            Path clone = tempDirectory.resolve("rewritten-clone");
            RepositoryRevision retained = RepositoryRevision.ofSha(fixture.seed().getRepository().resolve("refs/heads/main").getName());
            adapter.clone(clone, fixture.remote().toUri().toString());
            RepositoryRevision rewritten = rewriteRemoteHead(fixture.remote());
            adapter.fetch(clone, fixture.remote().toUri().toString());

            assertThatThrownBy(() -> adapter.verifyComparisonEndpoints(clone, retained, rewritten))
                    .isInstanceOf(RepositoryMutationException.class)
                    .hasMessageContaining("not reachable");
        }
    }
    @Test
    void fetches_from_the_configured_remote_when_the_persisted_origin_is_rewritten() throws Exception {
        try (RemoteFixture trusted = createRemote("trusted");
             RemoteFixture sentinel = createRemote("sentinel")) {
            JGitRepositoryAdapter adapter = adapter();
            RepositoryId repositoryId = RepositoryId.of("orders");
            Path managedParent = Files.createDirectories(tempDirectory.resolve("managed-checkouts"));
            Path workingTree = managedParent.resolve(repositoryId.value());
            String trustedUrl = trusted.remote().toUri().toString();
            RepositoryRuntimeRegistry registry = repositoryRegistry(repositoryId, managedParent, trustedUrl);
            RepositoryRevision admittedRevision = RepositoryRevision.ofSha(
                    trusted.seed().getRepository().resolve("refs/heads/main").getName());
            adapter.clone(workingTree, trustedUrl);

            RepositoryRevision trustedTip = RepositoryRevision.ofSha(
                    commit(trusted.seed(), trusted.seedRoot(), "trusted-only-tip"));
            pushBranch(trusted.seed(), "main");
            RepositoryRevision sentinelTip = RepositoryRevision.ofSha(
                    commit(sentinel.seed(), sentinel.seedRoot(), "sentinel-only-tip"));
            pushBranch(sentinel.seed(), "main");

            try (Git checkout = Git.open(workingTree.toFile())) {
                checkout.getRepository().getConfig().setString(
                        "remote", "origin", "url", sentinel.remote().toUri().toString());
                checkout.getRepository().getConfig().save();
            }

            new ExactRepositoryCheckout(registry, adapter, repository -> { })
                    .checkout(buildJob(repositoryId, admittedRevision));

            try (Git checkout = Git.open(workingTree.toFile())) {
                ObjectId fetchedMain = checkout.getRepository().resolve("refs/remotes/origin/main");
                assertThat(fetchedMain).isEqualTo(ObjectId.fromString(trustedTip.value()));
                assertThat(fetchedMain).isNotEqualTo(ObjectId.fromString(sentinelTip.value()));
            }
        }
    }

    @Test
    void rejects_an_analysis_writable_git_control_file_before_fetch_or_reclamation() throws Exception {
        requirePosixFileSystem();
        try (RemoteFixture fixture = createRemote("analysis-writable-control")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-analysis-writable-control");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            Path config = checkout.resolve(".git/config");
            UserPrincipal ownerBefore = Files.getOwner(config);
            Set<PosixFilePermission> writablePermissions = PosixFilePermissions.fromString("rw-rw-rw-");
            Files.setPosixFilePermissions(config, writablePermissions);
            ObjectId retainedRemoteTip;
            try (Git local = Git.open(checkout.toFile())) {
                retainedRemoteTip = local.getRepository().resolve("refs/remotes/origin/main");
            }
            assertThat(retainedRemoteTip).isNotNull();

            commit(fixture.seed(), fixture.seedRoot(), "unsafe-control-new-tip");
            pushBranch(fixture.seed(), "main");

            assertThatThrownBy(() -> adapter.fetch(checkout, fixture.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);

            try (Git local = Git.open(checkout.toFile())) {
                assertThat(local.getRepository().resolve("refs/remotes/origin/main")).isEqualTo(retainedRemoteTip);
            }
            assertThat(Files.getOwner(config)).isEqualTo(ownerBefore);
            assertThat(Files.getPosixFilePermissions(config)).isEqualTo(writablePermissions);
        }
    }

    @Test
    void rejects_a_hard_linked_git_control_entry_without_changing_an_outside_sentinel() throws Exception {
        requirePosixFileSystem();
        try (RemoteFixture fixture = createRemote("hard-linked-control")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-hard-linked-control");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            Path config = checkout.resolve(".git/config");
            Path sentinel = tempDirectory.resolve("outside-git-config-sentinel");
            byte[] configContents = Files.readAllBytes(config);
            Files.delete(config);
            Files.write(sentinel, configContents);
            Set<PosixFilePermission> writablePermissions = PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(sentinel, writablePermissions);
            createHardLinkOrSkip(config, sentinel);
            UserPrincipal sentinelOwner = Files.getOwner(sentinel);
            byte[] sentinelContents = Files.readAllBytes(sentinel);
            Set<PosixFilePermission> sentinelPermissions = Files.getPosixFilePermissions(sentinel);

            assertThat(Files.isSameFile(config, sentinel)).isTrue();
            assertThatThrownBy(() -> adapter.fetch(checkout, fixture.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);

            assertThat(Files.getOwner(sentinel)).isEqualTo(sentinelOwner);
            assertThat(Files.getPosixFilePermissions(sentinel)).isEqualTo(sentinelPermissions);
            assertThat(Files.readAllBytes(sentinel)).containsExactly(sentinelContents);
        }
    }

    @Test
    void rejects_unsafe_checkout_root_layouts_without_mutating_git_control_data() throws Exception {
        requirePosixFileSystem();
        try (RemoteFixture fixture = createRemote("unsafe-root")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-unsafe-root");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            Path config = checkout.resolve(".git/config");
            byte[] originalConfig = Files.readAllBytes(config);
            ObjectId originalRemoteTip;
            try (Git local = Git.open(checkout.toFile())) {
                originalRemoteTip = local.getRepository().resolve("refs/remotes/origin/main");
            }
            assertThat(((Number) Files.getAttribute(checkout, "unix:gid")).longValue())
                    .isEqualTo(JdtLsTestProperties.linuxUid().getAnalysisGid());
            assertThat((int) Files.getAttribute(checkout, "unix:mode") & 017777).isEqualTo(01770);

            for (int unsafeMode : new int[] {0755, 0770, 01777}) {
                Files.setAttribute(checkout, "unix:mode", unsafeMode);
                assertThatThrownBy(() -> adapter.fetch(checkout, fixture.remote().toUri().toString()))
                        .isInstanceOf(RepositoryMutationException.class);
                assertThat((int) Files.getAttribute(checkout, "unix:mode") & 017777).isEqualTo(unsafeMode);
                assertThat(Files.readAllBytes(config)).containsExactly(originalConfig);
                try (Git local = Git.open(checkout.toFile())) {
                    assertThat(local.getRepository().resolve("refs/remotes/origin/main"))
                            .isEqualTo(originalRemoteTip);
                }
            }

            Files.setAttribute(checkout, "unix:mode", 01770);
            JdtLsProperties policy = JdtLsTestProperties.linuxUid();
            JdtLsProperties wrongGroupPolicy = new JdtLsProperties(
                    policy.enabled(),
                    policy.home(),
                    policy.workspaceDataRoot(),
                    policy.javaExecutable(),
                    policy.isolationMode(),
                    policy.analysisUid(),
                    policy.analysisGid() + 1,
                    policy.analysisHome(),
                    policy.startupTimeout(),
                    policy.importTimeout(),
                    policy.requestTimeout(),
                    policy.maxActiveWorkspaces(),
                    policy.idleTimeout(),
                    policy.maintenanceInterval(),
                    policy.maxHeap());
            JGitRepositoryAdapter wrongGroupAdapter = new JGitRepositoryAdapter(
                    new RepositoryProperties(), wrongGroupPolicy);

            assertThatThrownBy(() -> wrongGroupAdapter.fetch(checkout, fixture.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);
            assertThat(((Number) Files.getAttribute(checkout, "unix:gid")).longValue()).isEqualTo(policy.analysisGid());
            assertThat(Files.readAllBytes(config)).containsExactly(originalConfig);
        }
    }

    @Test
    void rejects_git_file_indirection_before_fetch_mutates_the_external_repository() throws Exception {
        try (RemoteFixture remote = createRemote("gitdir-source")) {
            Path externalWorkTree = tempDirectory.resolve("external-worktree");
            try (Git external = Git.init().setInitialBranch("main").setDirectory(externalWorkTree.toFile()).call()) {
                commit(external, externalWorkTree, "external-worktree");
                external.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(remote.remote().toUri().toString()))
                        .call();
            }
            Path redirectedWorkTree = Files.createDirectories(tempDirectory.resolve("redirected-worktree"));
            JdtLsTestProperties.prepareSafeCheckoutRoot(redirectedWorkTree);
            Files.writeString(redirectedWorkTree.resolve(".git"),
                    "gitdir: " + externalWorkTree.resolve(".git").toAbsolutePath() + System.lineSeparator());
            try (Git external = Git.open(externalWorkTree.toFile())) {
                assertThat(external.getRepository().exactRef("refs/remotes/origin/main")).isNull();
            }

            JGitRepositoryAdapter adapter = adapter();
            assertThatThrownBy(() -> adapter.fetch(redirectedWorkTree, remote.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);

            try (Git external = Git.open(externalWorkTree.toFile())) {
                assertThat(external.getRepository().exactRef("refs/remotes/origin/main")).isNull();
            }
        }
    }

    @Test
    void rejects_object_alternates_before_fetch_uses_external_metadata() throws Exception {
        try (RemoteFixture fixture = createRemote("alternate-source")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-alternates");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            Path alternates = Files.createDirectories(checkout.resolve(".git/objects/info"))
                    .resolve("alternates");
            Files.writeString(alternates, fixture.remote().resolve("objects").toAbsolutePath()
                    + System.lineSeparator());

            assertThatThrownBy(() -> adapter.fetch(checkout, fixture.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);
        }
    }

    @Test
    void rejects_git_control_symlink_before_fetch_writes_refs_outside_the_checkout() throws Exception {
        try (RemoteFixture fixture = createRemote("control-symlink")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-control-symlink");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            Path originRefs = checkout.resolve(".git/refs/remotes/origin");
            Path retainedRefs = originRefs.resolveSibling("origin-retained");
            Path externalRefs = Files.createDirectories(tempDirectory.resolve("external-remote-refs"));
            Files.move(originRefs, retainedRefs);
            Files.createSymbolicLink(originRefs, externalRefs);

            assertThatThrownBy(() -> adapter.fetch(checkout, fixture.remote().toUri().toString()))
                    .isInstanceOf(RepositoryMutationException.class);

            assertThat(externalRefs.resolve("main")).doesNotExist();
        }
    }

    @Test
    void rejects_core_worktree_redirect_before_checkout_mutates_an_external_directory() throws Exception {
        try (RemoteFixture fixture = createRemote("worktree-redirect")) {
            JGitRepositoryAdapter adapter = adapter();
            Path checkout = tempDirectory.resolve("checkout-with-worktree-redirect");
            adapter.clone(checkout, fixture.remote().toUri().toString());
            RepositoryRevision revision = RepositoryRevision.ofSha(
                    fixture.seed().getRepository().resolve("refs/heads/main").getName());
            Path externalWorkTree = Files.createDirectories(tempDirectory.resolve("external-worktree-target"));
            Files.writeString(externalWorkTree.resolve("sentinel.txt"), "outside");
            try (Git local = Git.open(checkout.toFile())) {
                local.getRepository().getConfig().setString("core", null, "worktree", externalWorkTree.toString());
                local.getRepository().getConfig().save();
            }

            assertThatThrownBy(() -> adapter.checkoutDetached(checkout, revision))
                    .isInstanceOf(RepositoryMutationException.class);

            assertThat(Files.readString(externalWorkTree.resolve("sentinel.txt"))).isEqualTo("outside");
            assertThat(externalWorkTree.resolve("sample.txt")).doesNotExist();
        }
    }

    @Test
    void rejects_malformed_git_config_as_repository_mutation_failure() throws Exception {
        Path checkout = tempDirectory.resolve("checkout-with-malformed-config");
        Files.createDirectories(checkout);
        JdtLsTestProperties.prepareSafeCheckoutRoot(checkout);
        try (Git git = Git.init().setDirectory(checkout.toFile()).call()) {
            Path config = git.getRepository().getDirectory().toPath().resolve("config");
            Files.writeString(config, "[core\n");
        }

        assertThatThrownBy(() -> JGitWorktreeRepository.open(checkout, JdtLsTestProperties.linuxUid()))
                .isInstanceOf(RepositoryMutationException.class)
                .hasRootCauseInstanceOf(ConfigInvalidException.class);
    }

    private static RepositoryRevision rewriteRemoteHead(Path remote) throws Exception {
        try (Git bare = Git.open(remote.toFile()); ObjectInserter inserter = bare.getRepository().newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "rewritten\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append("sample.txt", org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob);
            ObjectId treeId = tree.insertTo(inserter);
            CommitBuilder commit = new CommitBuilder();
            PersonIdent identity = new PersonIdent("Test", "test@example.test");
            commit.setTreeId(treeId);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage("rewritten root");
            ObjectId rewritten = inserter.insert(Constants.OBJ_COMMIT, commit.build());
            inserter.flush();
            org.eclipse.jgit.lib.RefUpdate update = bare.getRepository().updateRef("refs/heads/main");
            update.setNewObjectId(rewritten);
            update.forceUpdate();
            return RepositoryRevision.ofSha(rewritten.name());
        }
    }

    private RepositoryRuntimeRegistry repositoryRegistry(
            RepositoryId repositoryId,
            Path managedParent,
            String remoteUrl) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(managedParent.toString());
        RepositoryProperties.RepositoryConfig repository = new RepositoryProperties.RepositoryConfig();
        repository.setUrl(remoteUrl);
        repository.setDefaultBranch("main");
        properties.setRepositories(Map.of(repositoryId.value(), repository));
        return new RepositoryRuntimeRegistry(properties);
    }

    private static IndexJob buildJob(RepositoryId repositoryId, RepositoryRevision revision) {
        return new IndexJob(new IndexJobId("job-1"), repositoryId,
                Optional.of(new IndexJobTarget(revision, new GenerationId("generation-1"), 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
    }

    private RemoteFixture createRemote() throws Exception {
        return createRemote("remote");
    }

    private RemoteFixture createRemote(String name) throws Exception {
        Path remote = tempDirectory.resolve(name + ".git");
        try (Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            assertThat(bare.getRepository().isBare()).isTrue();
        }
        Path seedRoot = tempDirectory.resolve(name + "-seed");
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
            bare.getRepository().updateRef(Constants.HEAD, true).link("refs/heads/main");
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

    private void requirePosixFileSystem() throws IOException {
        assumeTrue(Files.getFileStore(tempDirectory).supportsFileAttributeView("posix"),
                "Git control permission regressions require a POSIX filesystem");
    }

    private static void createHardLinkOrSkip(Path link, Path existing) throws IOException {
        try {
            Files.createLink(link, existing);
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "the temporary filesystem provider does not support hard links");
        }
    }

    private static JGitRepositoryAdapter adapter() {
        return new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid());
    }

}
