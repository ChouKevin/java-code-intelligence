package com.java.semantic.repository.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 儲存庫真實路徑收容規則 */
class RepositorySourceContainmentTest {

    private final RepositorySourceContainment containment = new RepositorySourceContainment();

    @Test
    void should_classify_only_contained_real_regular_files_as_local(@TempDir Path workspace) throws IOException {
        Path repositoryRoot = Files.createDirectory(workspace.resolve("repository"));
        Path source = write(repositoryRoot.resolve("src/main/java/com/acme/Order.java"), "class Order {}\n");
        Path outside = write(workspace.resolve("outside.java"), "class Outside {}\n");
        Path directory = Files.createDirectory(repositoryRoot.resolve("directory"));
        Path missing = repositoryRoot.resolve("missing.java");
        Path escape = repositoryRoot.resolve("escape.java");
        Files.createSymbolicLink(escape, outside);

        RepositorySourceContainmentResult contained = containment.classify(repositoryRoot, source);

        assertThat(contained).isEqualTo(new RepositorySourceContainmentResult.ContainedSource(
                source.toRealPath(), "src/main/java/com/acme/Order.java"));
        assertThat(containment.classify(repositoryRoot, outside))
                .isEqualTo(RepositorySourceContainmentResult.OutsideRepository.INSTANCE);
        assertThat(containment.classify(repositoryRoot, escape))
                .isEqualTo(RepositorySourceContainmentResult.OutsideRepository.INSTANCE);
        assertThat(containment.classify(repositoryRoot, missing))
                .isEqualTo(RepositorySourceContainmentResult.UnprovableSource.INSTANCE);
        assertThat(containment.classify(repositoryRoot, directory))
                .isEqualTo(RepositorySourceContainmentResult.UnprovableSource.INSTANCE);
    }

    private Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }
}
