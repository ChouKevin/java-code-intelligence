package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class JGitRepositoryAdapterComparisonTest {
    @TempDir
    Path repositoryDirectory;

    @BeforeEach
    void prepareManagedCheckoutRoot() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
    }

    @Test
    void root_commit_has_no_before_source_but_keeps_the_selected_java_addition() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("Source.java"), "class Source {}\n");
            Files.writeString(repositoryDirectory.resolve("application.properties"), "SECRET_MARKER\n");
            git.add().addFilepattern(".").call();
            RevCommit root = git.commit().setMessage("root").setAuthor("tester", "tester@example.test").call();
            org.eclipse.jgit.lib.RefUpdate remote = git.getRepository().updateRef("refs/remotes/origin/main");
            remote.setNewObjectId(root);
            remote.update();
            RepositoryRevision revision = RepositoryRevision.ofSha(root.name());
            JGitRepositoryAdapter adapter = adapter();
            SourceEvidencePolicy selected = policy(Set.of("Source.java"));

            assertThat(adapter.resolveReviewEndpoints(repositoryDirectory, ReviewSelection.commit(revision)).baselineRule())
                    .isEqualTo(ReviewBaselineRule.EMPTY_TREE);
            GitPreparedComparison comparison = adapter.prepareComparison(repositoryDirectory, Optional.empty(), revision,
                    policy(Set.of()), selected, Set.of(), Set.of());
            assertThat(comparison.previous()).isEmpty();
            assertThat(comparison.previousEntries()).isEmpty();
            assertThat(comparison.ancestry()).isEqualTo(GitComparisonAncestry.EMPTY_TREE);
            assertThat(comparison.currentEntries()).extracting(entry -> entry.path()).containsExactly("Source.java");
            assertThat(comparison.changes()).singleElement().satisfies(change -> {
                assertThat(change.kind()).isEqualTo(GitChangeKind.ADD);
                assertThat(change.newPath()).isEqualTo("Source.java");
                assertThat(change.patch()).contains("+class Source {}").doesNotContain("SECRET_MARKER");
            });
        }
    }

    @Test
    void excluded_rename_endpoint_never_produces_a_patch_in_either_direction() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("Source.java"), "class Source {}\n");
            git.add().addFilepattern(".").call();
            RevCommit before = git.commit().setMessage("before").setAuthor("tester", "tester@example.test").call();
            Files.move(repositoryDirectory.resolve("Source.java"), repositoryDirectory.resolve("application.properties"));
            git.rm().addFilepattern("Source.java").call();
            git.add().addFilepattern("application.properties").call();
            RevCommit after = git.commit().setMessage("after").setAuthor("tester", "tester@example.test").call();
            SourceEvidencePolicy beforePolicy = policy(Set.of("Source.java"));
            SourceEvidencePolicy afterPolicy = policy(Set.of());

            GitPreparedComparison forward = adapter().prepareComparison(repositoryDirectory,
                    Optional.of(RepositoryRevision.ofSha(before.name())), RepositoryRevision.ofSha(after.name()),
                    beforePolicy, afterPolicy, Set.of(), Set.of());
            GitPreparedComparison backward = adapter().prepareComparison(repositoryDirectory,
                    Optional.of(RepositoryRevision.ofSha(after.name())), RepositoryRevision.ofSha(before.name()),
                    afterPolicy, beforePolicy, Set.of(), Set.of());
            assertThat(forward.changes()).isEmpty();
            assertThat(backward.changes()).isEmpty();
            assertThat(forward.currentEntries()).isEmpty();
            assertThat(backward.currentEntries()).extracting(entry -> entry.path()).containsExactly("Source.java");
        }
    }

    @Test
    void merge_commit_uses_first_parent_and_reversed_range_preserves_requested_direction() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            Files.writeString(repositoryDirectory.resolve("Source.java"), "class Source {}\n");
            git.add().addFilepattern(".").call();
            RevCommit root = git.commit().setMessage("root").setAuthor("tester", "tester@example.test").call();
            git.checkout().setCreateBranch(true).setName("first").call();
            Files.writeString(repositoryDirectory.resolve("First.java"), "class First {}\n");
            git.add().addFilepattern(".").call();
            RevCommit first = git.commit().setMessage("first").setAuthor("tester", "tester@example.test").call();
            git.checkout().setCreateBranch(true).setName("second").setStartPoint(root.name()).call();
            Files.writeString(repositoryDirectory.resolve("Second.java"), "class Second {}\n");
            git.add().addFilepattern(".").call();
            RevCommit second = git.commit().setMessage("second").setAuthor("tester", "tester@example.test").call();
            git.checkout().setName("first").call();
            git.merge().include(second).setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).call();
            RevCommit merge = git.log().setMaxCount(1).call().iterator().next();
            org.eclipse.jgit.lib.RefUpdate remote = git.getRepository().updateRef("refs/remotes/origin/main");
            remote.setNewObjectId(merge);
            remote.update();
            JGitRepositoryAdapter adapter = adapter();
            RepositoryRevision firstRevision = RepositoryRevision.ofSha(first.name());
            RepositoryRevision mergeRevision = RepositoryRevision.ofSha(merge.name());
            assertThat(adapter.resolveReviewEndpoints(repositoryDirectory, ReviewSelection.commit(mergeRevision)).beforeRevision())
                    .contains(firstRevision);
            assertThat(adapter.resolveReviewEndpoints(repositoryDirectory,
                    ReviewSelection.range(mergeRevision, firstRevision)).beforeRevision()).contains(mergeRevision);
        }
    }

    private static JGitRepositoryAdapter adapter() {
        return new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid());
    }

    private static SourceEvidencePolicy policy(Set<String> selected) {
        return new SourceEvidencePolicy(SourceEvidencePolicy.VERSION, List.of("."), selected, Optional.empty());
    }
}
