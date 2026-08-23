package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdtWorkspaceSourceLocatorTest {

    @TempDir
    Path root;

    @Test
    void should_convert_only_a_contained_real_regular_request_source_to_a_file_uri() throws IOException {
        Path source = root.resolve("src/main/java/com/example/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example; class OrderService { }");
        RepositorySnapshot snapshot = snapshot();
        MethodTarget target = target("src/main/java/com/example/OrderService.java");

        assertThat(new JdtWorkspaceSourceLocator().sourceUri(snapshot, target))
                .isEqualTo(source.toRealPath().toUri().toString());
    }

    @Test
    void should_classify_proven_external_files_and_reject_invalid_provider_locations_without_path_leak()
            throws IOException {
        Path outside = Files.createTempFile("outside-source", ".java");
        Path sourceDirectory = root.resolve("src/main/java/com/example");
        Files.createDirectories(sourceDirectory);
        Path escape = sourceDirectory.resolve("Escape.java");
        Files.createSymbolicLink(escape, outside);
        RepositorySnapshot snapshot = snapshot();
        JdtWorkspaceSourceLocator locator = new JdtWorkspaceSourceLocator();

        assertRejected(() -> locator.sourceUri(snapshot, target("src/main/java/com/example/Escape.java")), outside.toString());
        assertThat(locator.localSource(snapshot, outside.toUri().toString())).isEmpty();
        assertRejected(() -> locator.localSource(snapshot, "not a uri"), "not a uri");
        assertRejected(() -> locator.localSource(snapshot, "http://example.invalid/source.java"), "example.invalid");
    }

    @Test
    void should_keep_jdt_results_external_and_non_invocable() {
        assertThat(new JdtWorkspaceSourceLocator().localSource(snapshot(), "jdt://contents/java.lang/String.class"))
                .isEmpty();
    }

    @Test
    void should_canonicalize_contained_alias_uris_to_the_real_source_uri() throws IOException {
        Path source = root.resolve("src/main/java/com/example/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example; class OrderService { }");
        Files.createDirectories(source.getParent().resolve("nested"));
        Path alias = source.getParent().resolve("./nested/../OrderService.java");

        assertThat(new JdtWorkspaceSourceLocator().localSourceUri(snapshot(), alias.toUri().toString()))
                .isEqualTo(source.toRealPath().toUri().toString());
    }

    @Test
    void should_treat_only_jdt_uris_as_external_and_reject_every_invalid_file_location() throws IOException {
        Path source = root.resolve("src/main/java/com/example/OrderService.java");
        Path directory = root.resolve("src/main/java/com/example/directory");
        Path missing = root.resolve("src/main/java/com/example/Missing.java");
        Path outside = Files.createTempFile("outside-source", ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example; class OrderService { }");
        Files.createDirectories(directory);
        Path escape = source.getParent().resolve("Escape.java");
        Files.createSymbolicLink(escape, outside);
        JdtWorkspaceSourceLocator locator = new JdtWorkspaceSourceLocator();

        assertThat(locator.isExternal(snapshot(), "jdt://contents/java.lang/String.class")).isTrue();
        assertThat(locator.isExternal(snapshot(), source.toUri().toString())).isFalse();
        assertThat(locator.isExternal(snapshot(), outside.toUri().toString())).isTrue();
        assertThat(locator.isExternal(snapshot(), escape.toUri().toString())).isTrue();
        assertRejected(() -> locator.isExternal(snapshot(), missing.toUri().toString()), missing.toString());
        assertRejected(() -> locator.isExternal(snapshot(), directory.toUri().toString()), directory.toString());
        assertRejected(() -> locator.isExternal(snapshot(), "not a uri"), "not a uri");
        assertRejected(() -> locator.isExternal(snapshot(), "http://example.invalid/source.java"), "example.invalid");
    }

    private void assertRejected(ThrowingRunnable action, String unsafeText) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SemanticProtocolException.class)
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain(unsafeText));
    }

    private RepositorySnapshot snapshot() {
        return new RepositorySnapshot(
                RepositoryId.of("orders"), root, RepositoryRevision.ofSha("a".repeat(40)));
    }

    private MethodTarget target(String sourceFile) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderService"),
                        sourceFile),
                "run",
                List.of());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
