package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.config.RepositoryProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JGitRepositoryAdapterComparisonTest {

    @TempDir
    Path repositoryDirectory;

    @Test
    void captures_complete_trees_and_marks_binary_invalid_and_oversized_content_unavailable() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("README.md"), "before\n");
            Files.write(repositoryDirectory.resolve("binary.bin"), new byte[] {1, 0, 2});
            Files.write(repositoryDirectory.resolve("invalid.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
            Files.write(repositoryDirectory.resolve("large.txt"), new byte[2 * 1024 * 1024 + 1]);
            Files.writeString(repositoryDirectory.resolve("pointer.txt"), "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 12\n");
            Files.writeString(repositoryDirectory.resolve("mentions-lfs.txt"), "See version https://git-lfs.github.com/spec/v1 for details.\n");
            git.add().addFilepattern(".").call();
            RevCommit previous = git.commit().setMessage("previous").setAuthor("tester", "tester@example.test").call();
            Files.writeString(repositoryDirectory.resolve("README.md"), "current\n");
            git.add().addFilepattern("README.md").call();
            RevCommit current = git.commit().setMessage("current").setAuthor("tester", "tester@example.test").call();

            GitPreparedComparison comparison = new JGitRepositoryAdapter(new RepositoryProperties()).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(previous.getId().getName()), RepositoryRevision.ofSha(current.getId().getName()));

            assertThat(comparison.previousEntries()).extracting(entry -> entry.path()).containsExactly("README.md", "binary.bin", "invalid.txt", "large.txt", "mentions-lfs.txt", "pointer.txt");
            assertThat(comparison.previousEntries()).filteredOn(entry -> entry.path().equals("binary.bin"))
                    .allSatisfy(entry -> {
                        assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.BINARY);
                        assertThat(entry.bytes()).isEmpty();
                    });
            assertThat(comparison.previousEntries()).filteredOn(entry -> entry.path().equals("invalid.txt"))
                    .allSatisfy(entry -> assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.UNSUPPORTED_ENCODING));
            assertThat(comparison.previousEntries()).filteredOn(entry -> entry.path().equals("large.txt"))
                    .allSatisfy(entry -> {
                        assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.TOO_LARGE);
                        assertThat(entry.byteLength()).isEqualTo(2L * 1024L * 1024L + 1L);
                        assertThat(entry.bytes()).isEmpty();
                    });
            assertThat(comparison.previousEntries()).filteredOn(entry -> entry.path().equals("pointer.txt"))
                    .allSatisfy(entry -> {
                        assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.LFS_POINTER);
                        assertThat(entry.bytes()).isEmpty();
                    });
            assertThat(comparison.previousEntries()).filteredOn(entry -> entry.path().equals("mentions-lfs.txt"))
                    .allSatisfy(entry -> assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.TEXT));
            assertThat(comparison.changes()).singleElement().satisfies(change -> assertThat(change.newPath()).isEqualTo("README.md"));
        }
    }

    @Test
    void preserves_direct_endpoint_ancestry_empty_and_rename_mode_and_unavailable_tree_entries() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("unchanged.txt"), "context\n");
            Files.writeString(repositoryDirectory.resolve("rename-source.txt"), "same contents\n");
            git.add().addFilepattern(".").call();
            RevCommit base = git.commit().setMessage("base").setAuthor("tester", "tester@example.test").call();

            Files.move(repositoryDirectory.resolve("rename-source.txt"), repositoryDirectory.resolve("rename-target.txt"));
            git.rm().addFilepattern("rename-source.txt").call();
            git.add().addFilepattern("rename-target.txt").call();
            addSpecialIndexEntries(git, "link", FileMode.SYMLINK, "target".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "module", FileMode.GITLINK, ObjectId.fromString("a".repeat(40)));
            RevCommit current = git.commit().setMessage("rename and modes").setAuthor("tester", "tester@example.test").call();

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties());
            GitPreparedComparison same = adapter.prepareComparison(repositoryDirectory, RepositoryRevision.ofSha(base.getId().getName()),
                    RepositoryRevision.ofSha(base.getId().getName()));
            GitPreparedComparison forward = adapter.prepareComparison(repositoryDirectory, RepositoryRevision.ofSha(base.getId().getName()),
                    RepositoryRevision.ofSha(current.getId().getName()));
            GitPreparedComparison reverse = adapter.prepareComparison(repositoryDirectory, RepositoryRevision.ofSha(current.getId().getName()),
                    RepositoryRevision.ofSha(base.getId().getName()));

            assertThat(same.ancestry()).isEqualTo(GitComparisonAncestry.SAME);
            assertThat(same.changes()).isEmpty();
            assertThat(same.previousEntries()).extracting(entry -> entry.path()).containsExactly("rename-source.txt", "unchanged.txt");
            assertThat(forward.ancestry()).isEqualTo(GitComparisonAncestry.PREVIOUS_ANCESTOR);
            assertThat(reverse.ancestry()).isEqualTo(GitComparisonAncestry.CURRENT_ANCESTOR);
            assertThat(forward.changes()).anySatisfy(change -> assertThat(change.kind()).isEqualTo(GitChangeKind.RENAME));
            assertThat(forward.currentEntries()).filteredOn(entry -> entry.path().equals("link"))
                    .allSatisfy(entry -> {
                        assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.SYMLINK);
                        assertThat(entry.bytes()).isEmpty();
                    });
            assertThat(forward.currentEntries()).filteredOn(entry -> entry.path().equals("module"))
                    .allSatisfy(entry -> {
                        assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.SUBMODULE);
                        assertThat(entry.bytes()).isEmpty();
                    });
        }
    }

    @Test
    void rejects_an_endpoint_not_present_in_the_configured_repository() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("README.md"), "text\n");
            git.add().addFilepattern("README.md").call();
            RevCommit commit = git.commit().setMessage("only").setAuthor("tester", "tester@example.test").call();

            assertThatThrownBy(() -> new JGitRepositoryAdapter(new RepositoryProperties()).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(commit.getId().getName()), RepositoryRevision.ofSha("f".repeat(40))))
                    .isInstanceOf(RepositoryMutationException.class);
        }
    }

    @Test
    void reports_diverged_history_from_the_exact_endpoints_without_using_a_merge_base() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("README.md"), "base\n");
            git.add().addFilepattern("README.md").call();
            RevCommit base = git.commit().setMessage("base").setAuthor("tester", "tester@example.test").call();
            Files.writeString(repositoryDirectory.resolve("README.md"), "left\n");
            git.add().addFilepattern("README.md").call();
            RevCommit left = git.commit().setMessage("left").setAuthor("tester", "tester@example.test").call();
            git.checkout().setName(base.getId().getName()).call();
            Files.writeString(repositoryDirectory.resolve("README.md"), "right\n");
            git.add().addFilepattern("README.md").call();
            RevCommit right = git.commit().setMessage("right").setAuthor("tester", "tester@example.test").call();

            GitPreparedComparison comparison = new JGitRepositoryAdapter(new RepositoryProperties()).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(left.getId().getName()), RepositoryRevision.ofSha(right.getId().getName()));

            assertThat(comparison.ancestry()).isEqualTo(GitComparisonAncestry.DIVERGED);
            assertThat(comparison.changes()).singleElement().satisfies(change -> {
                assertThat(change.oldBlobId()).isNotEqualTo(change.newBlobId());
                assertThat(change.patch()).contains("-left").contains("+right");
            });
        }
    }

    @Test
    void reports_add_delete_and_mode_only_changes_from_the_two_exact_trees() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("delete.txt"), "delete\n");
            Files.writeString(repositoryDirectory.resolve("mode.txt"), "mode\n");
            git.add().addFilepattern(".").call();
            RevCommit previous = git.commit().setMessage("previous").setAuthor("tester", "tester@example.test").call();
            ObjectId modeBlob = git.getRepository().resolve(previous.getTree().getId().getName() + ":mode.txt");
            Files.delete(repositoryDirectory.resolve("delete.txt"));
            Files.writeString(repositoryDirectory.resolve("add.txt"), "add\n");
            git.rm().addFilepattern("delete.txt").call();
            git.add().addFilepattern("add.txt").call();
            replaceIndexMode(git, "mode.txt", modeBlob, FileMode.EXECUTABLE_FILE);
            RevCommit current = git.commit().setMessage("current").setAuthor("tester", "tester@example.test").call();

            GitPreparedComparison comparison = new JGitRepositoryAdapter(new RepositoryProperties()).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(previous.getId().getName()), RepositoryRevision.ofSha(current.getId().getName()));

            assertThat(comparison.changes()).extracting(change -> change.kind()).containsExactlyInAnyOrder(GitChangeKind.ADD, GitChangeKind.DELETE, GitChangeKind.MODE);
            assertThat(comparison.changes()).filteredOn(change -> change.kind() == GitChangeKind.MODE)
                    .allSatisfy(change -> assertThat(change.oldBlobId()).isEqualTo(change.newBlobId()));
        }
    }

    @Test
    void preserves_a_complete_patch_larger_than_one_allowed_text_file() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            RepositoryProperties properties = new RepositoryProperties();
            long fileLimit = 128L * 1024L;
            properties.setGitEvidenceFileTextBytes(fileLimit);
            properties.setGitEvidenceSnapshotTextBytes(512L * 1024L);
            Files.writeString(repositoryDirectory.resolve("rewrite.txt"), "\n".repeat((int) fileLimit));
            git.add().addFilepattern("rewrite.txt").call();
            RevCommit previous = git.commit().setMessage("previous").setAuthor("tester", "tester@example.test").call();
            Files.writeString(repositoryDirectory.resolve("rewrite.txt"), "\r\n".repeat((int) (fileLimit / 2L)));
            git.add().addFilepattern("rewrite.txt").call();
            RevCommit current = git.commit().setMessage("current").setAuthor("tester", "tester@example.test").call();

            GitPreparedComparison comparison = new JGitRepositoryAdapter(properties).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(previous.getId().getName()), RepositoryRevision.ofSha(current.getId().getName()));

            assertThat(comparison.changes()).singleElement().satisfies(change -> {
                assertThat(change.diffStatus()).isEqualTo("AVAILABLE");
                assertThat(change.patch().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan((int) (2L * fileLimit + 64L * 1024L));
                assertThat(change.patchChunks()).hasSizeGreaterThan(1);
            });
        }
    }

    @Test
    void fails_the_entire_preparation_when_exact_snapshot_text_exceeds_its_total_budget() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            RepositoryProperties properties = new RepositoryProperties();
            properties.setGitEvidenceFileTextBytes(64L);
            properties.setGitEvidenceSnapshotTextBytes(8L);
            Files.writeString(repositoryDirectory.resolve("first.txt"), "first\n");
            Files.writeString(repositoryDirectory.resolve("second.txt"), "second\n");
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage("over budget").setAuthor("tester", "tester@example.test").call();

            assertThatThrownBy(() -> new JGitRepositoryAdapter(properties).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(commit.getId().getName()), RepositoryRevision.ofSha(commit.getId().getName())))
                    .isInstanceOf(RepositoryMutationException.class)
                    .hasRootCauseMessage("exact snapshot text budget exceeded");
        }
    }

    @Test
    void classifies_a_non_utf8_raw_git_path_without_using_replacement_decoding() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call();
             ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "text\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append(new byte[] {(byte) 0xC3, (byte) 0x28}, FileMode.REGULAR_FILE, blob);
            ObjectId treeId = tree.insertTo(inserter);
            CommitBuilder builder = new CommitBuilder();
            builder.setTreeId(treeId);
            PersonIdent identity = new PersonIdent("tester", "tester@example.test");
            builder.setAuthor(identity);
            builder.setCommitter(identity);
            builder.setMessage("invalid path");
            ObjectId commitId = inserter.insert(Constants.OBJ_COMMIT, builder.build());
            inserter.flush();

            GitPreparedComparison comparison = new JGitRepositoryAdapter(new RepositoryProperties()).prepareComparison(repositoryDirectory,
                    RepositoryRevision.ofSha(commitId.name()), RepositoryRevision.ofSha(commitId.name()));

            assertThat(comparison.previousEntries()).singleElement().satisfies(entry -> {
                assertThat(entry.path()).isEqualTo("raw-path-hex:c328");
                assertThat(entry.contentStatus()).isEqualTo(GitFileContentStatus.UNSUPPORTED_PATH);
                assertThat(entry.bytes()).isEmpty();
            });
        }
    }

    private static void addSpecialIndexEntries(Git git, String symlinkPath, FileMode symlinkMode, byte[] symlinkBytes,
                                               String gitlinkPath, FileMode gitlinkMode, ObjectId gitlinkTarget) throws Exception {
        ObjectId symlinkBlob;
        try (ObjectInserter inserter = git.getRepository().newObjectInserter()) {
            symlinkBlob = inserter.insert(Constants.OBJ_BLOB, symlinkBytes);
            inserter.flush();
        }
        DirCache cache = git.getRepository().lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            editor.add(new PathEdit(symlinkPath) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(symlinkMode);
                    entry.setObjectId(symlinkBlob);
                }
            });
            editor.add(new PathEdit(gitlinkPath) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(gitlinkMode);
                    entry.setObjectId(gitlinkTarget);
                }
            });
            editor.finish();
            cache.write();
            cache.commit();
        } finally {
            cache.unlock();
        }
    }

    private static void replaceIndexMode(Git git, String path, ObjectId blob, FileMode mode) throws Exception {
        DirCache cache = git.getRepository().lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            editor.add(new PathEdit(path) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(mode);
                    entry.setObjectId(blob);
                }
            });
            editor.finish();
            cache.write();
            cache.commit();
        } finally {
            cache.unlock();
        }
    }
}
