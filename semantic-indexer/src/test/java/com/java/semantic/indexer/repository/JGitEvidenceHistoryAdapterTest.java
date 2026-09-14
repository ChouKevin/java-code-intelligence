package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.config.RepositoryProperties;
import org.eclipse.jgit.api.Git;
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
        try (Git git = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            Files.writeString(repositoryRoot.resolve("Evidence.java"), "class Evidence { }");
            git.add().addFilepattern(".").call();
            String first = git.commit().setMessage("first").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();
            Files.writeString(repositoryRoot.resolve("Evidence.java"), "class Evidence { int version; }");
            git.add().addFilepattern(".").call();
            String head = git.commit().setMessage("second").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();

            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(mock(RepositoryProperties.class));
            List<com.java.semantic.model.git.GitCommit> commits = new ArrayList<>();
            adapter.streamReachableHistory(repositoryRoot, RepositoryRevision.ofSha(head), commits::add);

            assertThat(commits).extracting(commit -> commit.revision().value()).containsExactly(head, first);
            assertThat(commits.getFirst().parents()).extracting(RepositoryRevision::value).containsExactly(first);
            assertThat(commits.get(1).parents()).isEmpty();
        }
    }
}
