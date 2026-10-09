package com.java.semantic.indexer.source;

import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceCapacityGuardTest {
    @TempDir Path root;

    @Test
    void combined_roots_allow_the_exact_limit_and_reject_one_byte_over() throws IOException {
        Path admin = root.resolve("admin");
        Path published = root.resolve("published");
        createRoots(admin, published);
        Files.writeString(admin.resolve("git-object"), "ab");
        Files.writeString(published.resolve("source-file"), "cde");

        RepositoryProperties properties = properties(admin, published);
        properties.getSourceCapacity().setMaxManagedBytes(5);
        SourceCapacityGuard guard = new SourceCapacityGuard(properties);

        guard.check();

        Files.writeString(published.resolve("extra"), "f");
        assertFailure(guard, "CAPACITY_EXCEEDED");
    }

    @Test
    void nested_managed_root_is_counted_only_once() throws IOException {
        Path admin = root.resolve("admin");
        Path published = admin.resolve("published");
        createRoots(admin, published);
        Files.writeString(admin.resolve("git-object"), "ab");
        Files.writeString(published.resolve("source-file"), "cde");

        RepositoryProperties properties = properties(admin, published);
        properties.getSourceCapacity().setMaxManagedBytes(5);
        SourceCapacityGuard guard = new SourceCapacityGuard(properties);

        guard.check();

        Files.writeString(admin.resolve("extra"), "f");
        assertFailure(guard, "CAPACITY_EXCEEDED");
    }

    @Test
    void refuses_to_measure_a_managed_symlink() throws IOException {
        Path admin = root.resolve("admin");
        Path published = root.resolve("published");
        createRoots(admin, published);
        Path target = root.resolve("outside-source");
        Files.writeString(target, "source bytes");
        Files.createSymbolicLink(admin.resolve("source-link"), target);

        RepositoryProperties properties = properties(admin, published);
        properties.getSourceCapacity().setMaxManagedBytes(Long.MAX_VALUE);

        assertFailure(new SourceCapacityGuard(properties), "CAPACITY_UNAVAILABLE");
    }

    @Test
    void refuses_to_measure_when_a_managed_root_is_missing() throws IOException {
        Path admin = root.resolve("admin");
        Files.createDirectories(admin);
        RepositoryProperties properties = properties(admin, root.resolve("missing-published"));
        properties.getSourceCapacity().setMaxManagedBytes(Long.MAX_VALUE);

        assertFailure(new SourceCapacityGuard(properties), "CAPACITY_UNAVAILABLE");
    }

    @Test
    void minimum_free_space_is_enforced_when_managed_byte_limit_is_disabled() throws IOException {
        Path admin = root.resolve("admin");
        Path published = root.resolve("published");
        createRoots(admin, published);
        RepositoryProperties properties = properties(admin, published);
        properties.getSourceCapacity().setMaxManagedBytes(0);
        properties.getSourceCapacity().setMinFreeBytes(Long.MAX_VALUE);

        assertFailure(new SourceCapacityGuard(properties), "CAPACITY_EXCEEDED");
    }

    @Test
    void capacity_settings_reject_negative_values() {
        RepositoryProperties.SourceCapacityConfig capacity = new RepositoryProperties.SourceCapacityConfig();

        assertThatThrownBy(() -> capacity.setMaxManagedBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capacity.setMinFreeBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RepositoryProperties properties(Path admin, Path published) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setSourceAdminRoot(admin.toString());
        properties.setSourcePublishedRoot(published.toString());
        return properties;
    }

    private static void createRoots(Path admin, Path published) throws IOException {
        Files.createDirectories(admin);
        Files.createDirectories(published);
    }

    private void assertFailure(SourceCapacityGuard guard, String failureCode) {
        assertThatThrownBy(guard::check)
                .isInstanceOfSatisfying(SourceCapacityGuard.CapacityException.class, exception -> {
                    assertThat(exception.failureCode()).isEqualTo(failureCode);
                    assertThat(exception.getMessage()).doesNotContain(root.toString());
                });
    }
}
