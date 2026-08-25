package com.java.semantic.indexer.incremental;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JGitRevisionDiffAdapterTest {

    @TempDir
    private Path repositoryRoot;

    @Test
    void should_report_rename_with_old_and_new_paths_from_exact_revisions() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            Files.createDirectories(repositoryRoot.resolve("src"));
            Files.writeString(repositoryRoot.resolve("src/Before.java"), "class Before { int value; }");
            Files.writeString(repositoryRoot.resolve("src/Modify.java"), "class Modify { int oldValue; }");
            Files.writeString(repositoryRoot.resolve("src/Delete.java"), "class Delete { int value; }");
            git.add().addFilepattern(".").call();
            String published = git.commit().setMessage("published").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();
            Files.move(repositoryRoot.resolve("src/Before.java"), repositoryRoot.resolve("src/After.java"));
            Files.writeString(repositoryRoot.resolve("src/Modify.java"), "class Modify { int newValue; }");
            Files.writeString(repositoryRoot.resolve("src/Add.java"), "class Add { int value; }");
            git.rm().addFilepattern("src/Before.java").call();
            git.rm().addFilepattern("src/Delete.java").call();
            git.add().addFilepattern(".").call();
            String selected = git.commit().setMessage("selected").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call().getId().name();

            JGitRevisionDiffAdapter adapter = new JGitRevisionDiffAdapter(git.getRepository());

            assertThat(adapter.diff(published, selected)).containsExactly(
                    ChangedSource.add("src/Add.java", "class Add { int value; }"),
                    ChangedSource.rename("src/Before.java", "src/After.java", "class Before { int value; }",
                            "class Before { int value; }"),
                    ChangedSource.delete("src/Delete.java", "class Delete { int value; }"),
                    ChangedSource.modify("src/Modify.java", "class Modify { int oldValue; }",
                            "class Modify { int newValue; }"));
        }
    }
}
