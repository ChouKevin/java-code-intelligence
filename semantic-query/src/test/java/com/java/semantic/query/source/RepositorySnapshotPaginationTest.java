package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.source.SourceReadContract.RepositoryCollection;
import com.java.semantic.model.source.SourceReadContract.RepositoryRequest;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RepositorySnapshotPaginationTest {
    @TempDir Path temp;

    @Test
    void atomic_registry_replacement_never_binds_old_descriptors_to_new_digest() throws Exception {
        Path published = Files.createDirectory(temp.resolve("published"));
        Path registry = published.resolve("repositories.json");
        Path replacement = published.resolve("replacement.json");
        JsonMapper writer = JsonMapper.builder().build();
        Files.write(registry, writer.writeValueAsBytes(List.of(
                new SourceRepositoryDescriptor("sample", "Sample A", "main", Optional.empty()),
                new SourceRepositoryDescriptor("zeta", "Zeta A", "main", Optional.empty()))));
        Files.write(replacement, writer.writeValueAsBytes(List.of(
                new SourceRepositoryDescriptor("sample", "Sample B", "main", Optional.empty()),
                new SourceRepositoryDescriptor("zeta", "Zeta B", "main", Optional.empty()))));
        SourceAccessProperties properties = new SourceAccessProperties(published,
                Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")),
                List.of("sample", "zeta"), 65_536, Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(2), 2);
        JsonMapper mapper = new JsonMapper() {
            private boolean replaced;
            @Override public JsonNode readTree(byte[] bytes) {
                JsonNode snapshot = super.readTree(bytes);
                if (!replaced) {
                    replaced = true;
                    try { Files.move(replacement, registry, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING); }
                    catch (java.io.IOException exception) { throw new AssertionError(exception); }
                }
                return snapshot;
            }
        };
        LocalSourceRevisionCatalog catalog = new LocalSourceRevisionCatalog(properties, mapper);
        RepositoryCollection first = catalog.listRepositories(new RepositoryRequest(Optional.empty(), 1, Optional.empty()));
        assertThat(first.items()).extracting(item -> item.displayName()).containsExactly("Sample A");
        assertThat(first.page().nextCursor()).isPresent();
        assertThatThrownBy(() -> catalog.listRepositories(new RepositoryRequest(Optional.empty(), 1,
                first.page().nextCursor()))).isInstanceOfSatisfying(SourceQueryException.class,
                        error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
        RepositoryCollection newPage = catalog.listRepositories(new RepositoryRequest(Optional.empty(), 1, Optional.empty()));
        assertThat(newPage.items()).extracting(item -> item.displayName()).containsExactly("Sample B");
    }
}
