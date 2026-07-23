package com.java.semantic.repository.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositorySnapshotTest {

    private static final RepositorySnapshot SNAPSHOT = new RepositorySnapshot(
            RepositoryId.of("orders"), Path.of("/fixture"), RepositoryRevision.fixture());

    @Test
    void should_return_normalized_relative_source_file_only_for_in_root_file_uris() {
        assertThat(SNAPSHOT.relativeSourceFile("file:///fixture/src/../src/Main.java"))
                .contains("src/Main.java");
        assertThat(SNAPSHOT.relativeSourceFile("file:///outside/Other.java")).isNotPresent();
        assertThat(SNAPSHOT.relativeSourceFile("not a valid uri")).isNotPresent();
        assertThat(SNAPSHOT.relativeSourceFile("https://example.test/Other.java")).isNotPresent();
    }
}
