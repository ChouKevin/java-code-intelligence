package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceContextContractTest {
    @TempDir Path temp;

    @Test
    void unprepared_and_unknown_sha_never_trigger_source_preparation() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        Files.write(fixture.root.resolve("repositories.json"), fixture.mapper.writeValueAsBytes(List.of(
                new com.java.semantic.model.source.SourceRepositoryDescriptor("sample", "Sample", "main", Optional.empty()))));
        assertThat(fixture.catalog().getContext(new ContextRequest("sample", Optional.empty())).sourceStatus())
                .isEqualTo(SourceStatus.NOT_PREPARED);
        assertThatThrownBy(() -> fixture.catalog().admit(fixture.context))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_NOT_PREPARED));
        fixture.file("Guide.md", "guide");
        fixture.publish(Optional.empty());
        assertThatThrownBy(() -> fixture.catalog().admit(new SourceContext("sample",
                "abcdef0123456789abcdef0123456789abcdef01")))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.REVISION_NOT_PREPARED));
    }

    @Test
    void complete_orphan_tree_does_not_become_a_published_context() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("safe.java", "complete-but-unpublished");
        fixture.publish(Optional.empty());
        Files.delete(fixture.root.resolve("sample/state.json"));
        assertThat(fixture.catalog().getContext(new ContextRequest("sample", Optional.empty())).sourceStatus())
                .isEqualTo(SourceStatus.NOT_PREPARED);
        assertThat(fixture.catalog().getContext(new ContextRequest("sample", Optional.empty())).context()).isEmpty();
        assertThatThrownBy(() -> fixture.catalog().admit(fixture.context))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_NOT_PREPARED));
    }

    @Test
    void exact_membership_and_manifest_digest_control_readiness() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Guide.md", "guide");
        fixture.publish(Optional.of("Guide.md"));
        ContextResult context = fixture.catalog().getContext(new ContextRequest("sample", Optional.of(SourceFilesystemFixture.SHA)));
        assertThat(context.context()).contains(fixture.context);
        assertThat(context.projectGuide().orElseThrow().freshness()).isEqualTo(GuideFreshness.NOT_VERIFIED);
        assertThat(fixture.catalog().listRepositories(new RepositoryRequest(Optional.empty(), 20, Optional.empty()))
                .items().getFirst().revision()).contains(SourceFilesystemFixture.SHA);
        Files.writeString(fixture.tree.getParent().resolve("manifest.json"), "{}");
        assertThatThrownBy(() -> fixture.catalog().admit(fixture.context))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
    }
    @Test
    void registry_intersection_and_oversized_metadata_fail_closed() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("safe.java", "source");
        fixture.publish(Optional.empty());
        SourceAccessProperties denyAll = new SourceAccessProperties(fixture.root, fixture.properties.rgExecutable(),
                List.of(), 65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        LocalSourceRevisionCatalog hidden = new LocalSourceRevisionCatalog(denyAll, fixture.mapper, fixture.locks);
        assertThat(hidden.listRepositories(new RepositoryRequest(Optional.empty(), 20, Optional.empty())).items()).isEmpty();
        assertThatThrownBy(() -> hidden.admit(fixture.context))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.REPOSITORY_NOT_FOUND));
        Files.writeString(fixture.tree.getParent().resolve("manifest.json"), "x".repeat(65_537));
        assertThatThrownBy(() -> fixture.catalog().admit(fixture.context))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
    }

    @Test
    void directory_cursor_advances_past_its_git_key_even_when_file_shares_prefix() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("a");
        fixture.file("a.java", "file");
        fixture.file("a/z.java", "nested");
        fixture.file("é.java", "unicode");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            LocalRepositorySourceService service = fixture.service();
            FileCollection first = service.listFiles(admitted, new FileListRequest(fixture.context, "", 1, Optional.empty()));
            assertThat(first.items()).extracting(FileEntry::path).containsExactly("a.java");
            FileCollection second = service.listFiles(admitted, new FileListRequest(fixture.context, "", 1,
                    first.page().nextCursor()));
            assertThat(second.items()).extracting(FileEntry::path).containsExactly("a");
            FileCollection third = service.listFiles(admitted, new FileListRequest(fixture.context, "", 1,
                    second.page().nextCursor()));
            assertThat(third.items()).extracting(FileEntry::path).containsExactly("é.java");
            assertThat(third.page().hasMore()).isFalse();
        }
    }

}
