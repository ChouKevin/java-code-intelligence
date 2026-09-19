package com.java.semantic.semantic.adapter.jdtls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportedSourceRootPolicyTest {

    @TempDir
    Path tempDirectory;

    @Test
    void should_keep_empty_and_excluded_roots_declared_while_refusing_a_symlinked_source_root() throws Exception {
        Path repository = Files.createDirectories(tempDirectory.resolve("repository"));
        Path project = Files.createDirectories(repository.resolve("module"));
        Path outside = Files.createDirectories(tempDirectory.resolve("outside"));
        Files.createDirectories(project.resolve("src"));
        Files.createSymbolicLink(project.resolve("src/main-linked"), outside);

        List<ImportedSourceRootPolicy.Root> roots = ImportedSourceRootPolicy.inventory(repository, project,
                List.of("src/main/java", "src/test/java", "target/generated-sources", "src/main-linked"));

        assertThat(roots).extracting(root -> root.analysisRoot().path())
                .containsExactly("module/src/main/java", "module/src/test/java", "module/target/generated-sources",
                        "module/src/main-linked");
        assertThat(roots).filteredOn(root -> root.analysisRoot().included())
                .extracting(root -> root.analysisRoot().path()).containsExactly("module/src/main/java");
        assertThat(roots).filteredOn(root -> root.analysisRoot().path().equals("module/src/main-linked"))
                .singleElement().satisfies(root -> assertThat(root.analysisRoot().exclusions()).contains("symlink"));
    }
}
