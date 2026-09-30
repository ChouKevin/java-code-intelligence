package com.java.semantic.indexer.build;

import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.ProjectGuideState;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectGuideReaderTest {
    private static final RepositoryId REPOSITORY = RepositoryId.of("orders");
    private static final RepositoryRevision IMPORTED = RepositoryRevision.ofSha("2".repeat(40));
    private static final String PATH = "docs/codebase/overview.md";
    private final ProjectGuideReader reader = new ProjectGuideReader();

    @Test
    void preserves_author_analyzed_revision_separately_from_imported_revision() {
        String guide = """
                # Analysis baseline
                ```json
                {"formatVersion":1,"promptVersion":1,"repositoryId":"orders",
                 "analyzedRevision":"1111111111111111111111111111111111111111",
                 "generatedAt":"2026-09-28T12:30:00Z",
                 "sourceScope":{"includedPaths":["src/main/java"],"excludedPaths":["test"],"limitations":["runtime not verified"]}}
                ```
                # Navigation
                Read Order.java.
                """;
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(entry(guide)), 4096))
                .satisfies(membership -> {
                    assertThat(membership.state()).isEqualTo(ProjectGuideState.AVAILABLE);
                    assertThat(membership.importedRevision()).contains(IMPORTED);
                    assertThat(membership.provenance().orElseThrow().analyzedRevision().value()).isEqualTo("1".repeat(40));
                    assertThat(membership.freshness()).isEqualTo("NOT_VERIFIED");
                });
    }

    @Test
    void invalid_or_missing_guide_does_not_admit_a_readable_file() {
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.empty(), 4096).state())
                .isEqualTo(ProjectGuideState.ABSENT);
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.empty(), Optional.empty(), 4096).state())
                .isEqualTo(ProjectGuideState.DISABLED);
        String wrongRepository = """
                ```json
                {"formatVersion":1,"promptVersion":1,"repositoryId":"payments",
                 "analyzedRevision":"1111111111111111111111111111111111111111",
                 "generatedAt":"2026-09-28T12:30:00Z",
                 "sourceScope":{"includedPaths":[],"excludedPaths":[],"limitations":[]}}
                ```
                """;
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(entry(wrongRepository)), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(entry("other text")), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        GitSnapshotEntry link = new GitSnapshotEntry(PATH, "120000", "a".repeat(40), GitFileContentStatus.SYMLINK,
                7L, new byte[0]);
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(link), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        GitSnapshotEntry submodule = new GitSnapshotEntry(PATH, "160000", "a".repeat(40),
                GitFileContentStatus.SUBMODULE, 0L, new byte[0]);
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(submodule), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        String malformedShape = """
                ```json
                {"formatVersion":1,"promptVersion":1,"repositoryId":"orders",
                 "analyzedRevision":17,"generatedAt":"2026-09-28T12:30:00Z",
                 "sourceScope":{"includedPaths":[],"excludedPaths":[],"limitations":[]}}
                ```
                """;
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(entry(malformedShape)), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        String missingAnalyzedRevision = """
                ```json
                {"formatVersion":1,"promptVersion":1,"repositoryId":"orders",
                 "generatedAt":"2026-09-28T12:30:00Z",
                 "sourceScope":{"includedPaths":[],"excludedPaths":[],"limitations":[]}}
                ```
                """;
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(entry(missingAnalyzedRevision)), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
        GitSnapshotEntry invalidUtf8 = new GitSnapshotEntry(PATH, "100644", "a".repeat(40),
                GitFileContentStatus.TEXT, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThat(reader.read(REPOSITORY, IMPORTED, Optional.of(PATH), Optional.of(invalidUtf8), 4096).state())
                .isEqualTo(ProjectGuideState.INVALID);
    }

    private static GitSnapshotEntry entry(String value) {
        return new GitSnapshotEntry(PATH, "100644", "a".repeat(40), GitFileContentStatus.TEXT,
                value.getBytes(StandardCharsets.UTF_8));
    }
}
