package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** 掃描器的兩條路徑規則：測試原始碼排除與 symlink 收容檢查 */
class SourceFileScannerTest {

    private final SourceFileScanner scanner = new SourceFileScanner();

    @Test
    void should_still_scan_a_repository_when_its_own_checkout_path_contains_the_test_source_marker(
            @TempDir Path tempDir) throws IOException {
        Path repositoryRoot = tempDir.resolve("src/test/checkout/order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Order.java"), "package com.example; class Order {}");

        List<SourceFile> files = scanner.scan(repositoryRoot, List.of(repositoryRoot.resolve("src/main/java")));

        assertThat(files)
                .as("舊分析器比對絕對路徑，這種 checkout 路徑會讓整個 repo 靜默掃出零筆")
                .hasSize(1);
    }

    @Test
    void should_skip_test_sources_when_they_sit_under_the_repository_relative_test_path(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path mainSource = repositoryRoot.resolve("src/main/java/com/example");
        Path testSource = repositoryRoot.resolve("module/src/test/java/com/example");
        Files.createDirectories(mainSource);
        Files.createDirectories(testSource);
        Files.writeString(mainSource.resolve("Order.java"), "package com.example; class Order {}");
        Files.writeString(testSource.resolve("OrderTest.java"), "package com.example; class OrderTest {}");

        List<SourceFile> files = scanner.scan(repositoryRoot,
                List.of(repositoryRoot.resolve("src/main/java"), repositoryRoot.resolve("module/src/test/java")));

        assertThat(files).extracting(file -> file.path().getFileName().toString())
                .containsExactly("Order.java");
    }

    @Test
    void should_refuse_a_source_file_when_a_symlink_resolves_outside_the_repository(@TempDir Path tempDir)
            throws IOException {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path secret = outside.resolve("Secret.java");
        Files.writeString(secret, "package com.example; class Secret {}");

        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Order.java"), "package com.example; class Order {}");
        Files.createSymbolicLink(sourceRoot.resolve("Linked.java"), secret);

        List<SourceFile> files = scanner.scan(repositoryRoot, List.of(repositoryRoot.resolve("src/main/java")));

        assertThat(files).extracting(file -> file.path().getFileName().toString())
                .as("normalize 是純字面運算，攔不住解析到 repo 之外的 symlink")
                .containsExactly("Order.java");
    }

    @Test
    void should_accept_a_symlink_when_it_resolves_back_inside_the_repository(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java/com/example");
        Path shared = repositoryRoot.resolve("shared");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(shared);
        Path target = shared.resolve("Shared.java");
        Files.writeString(target, "package com.example; class Shared {}");
        Files.createSymbolicLink(sourceRoot.resolve("Shared.java"), target);

        List<SourceFile> files = scanner.scan(repositoryRoot, List.of(repositoryRoot.resolve("src/main/java")));

        assertThat(files).extracting(file -> file.path().getFileName().toString())
                .containsExactly("Shared.java");
    }

    @Test
    void should_expose_the_source_root_relative_path_when_a_file_is_scanned(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example"));
        Files.writeString(sourceRoot.resolve("com/example/Order.java"), "package com.example; class Order {}");

        List<SourceFile> files = scanner.scan(repositoryRoot, List.of(sourceRoot));

        assertThat(files.get(0).relativePath()).isEqualTo("com/example/Order.java");
    }
}
