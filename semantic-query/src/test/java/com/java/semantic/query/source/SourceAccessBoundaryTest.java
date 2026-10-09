package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceReadContract.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAccessBoundaryTest {
    @TempDir Path temp;

    @Test
    void direct_children_use_git_tree_keys_across_unicode_and_directory_prefixes() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("a");
        fixture.directory("a/子");
        fixture.file("a/子/One.java", "1");
        fixture.file("a.java", "2");
        fixture.file("中.java", "3");
        fixture.file("🙂.java", "4");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            List<String> paths = new ArrayList<>();
            Optional<String> cursor = Optional.empty();
            do {
                FileCollection page = fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "", 1, 1, cursor));
                paths.addAll(page.items().stream().map(FileEntry::path).toList());
                cursor = page.page().nextCursor();
            } while (cursor.isPresent());
            assertThat(paths).containsExactly("a.java", "a", "中.java", "🙂.java");
            assertThat(fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "a", 1, 20, Optional.empty()))
                    .items()).extracting(FileEntry::path).containsExactly("a/子");
        }
    }

    @Test
    void excluded_traversal_symlinks_and_untracked_files_do_not_become_source() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("safe.java", "safe");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Files.writeString(fixture.tree.resolve("untracked.java"), "untracked");
            assertThatThrownBy(() -> readRaw(fixture, admitted, "untracked.java"))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                            .isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
            assertThatThrownBy(() -> readRaw(fixture, admitted, "target/safe.java"))
                    .isInstanceOf(SourceQueryException.class);
            assertThatThrownBy(() -> readRaw(fixture, admitted, "../safe.java"))
                    .isInstanceOf(IllegalArgumentException.class);
            Files.delete(fixture.tree.resolve("safe.java"));
            Files.createSymbolicLink(fixture.tree.resolve("safe.java"), Path.of("untracked.java"));
            assertThatThrownBy(() -> readRaw(fixture, admitted, "safe.java"))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                            .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test
    void symlink_parent_is_denied_by_listing_search_and_direct_read() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/safe.java", "sealed-source");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Path outside = temp.resolve("outside-published");
            Files.move(fixture.tree.resolve("src"), outside);
            Files.createSymbolicLink(fixture.tree.resolve("src"), outside);
            assertThatThrownBy(() -> fixture.service().listFiles(admitted,
                    new FileListRequest(fixture.context, "src", 1, 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                            .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
            assertThatThrownBy(() -> fixture.service().searchText(admitted,
                    new TextSearchRequest(fixture.context, "sealed-source", "src", Optional.empty(), 20)))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                            .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
            assertThatThrownBy(() -> readRaw(fixture, admitted, "src/safe.java"))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                            .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test
    void cursor_from_other_directory_is_not_admitted() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "a");
        fixture.file("b.java", "b");
        fixture.directory("sub");
        fixture.file("sub/c.java", "c");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            FileCollection first = fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "", 1, 1, Optional.empty()));
            assertThatThrownBy(() -> fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "sub", 1, 1, first.page().nextCursor()))).isInstanceOf(SourceQueryException.class);
        }
    }

    private static SourceResult readRaw(SourceFilesystemFixture fixture, AdmittedSourceRevision admitted, String path) {
        ReadSourceRequest request = new ReadSourceRequest(fixture.context, path, View.RAW, Optional.of(1),
                List.of(), Optional.empty(), 1, 0, 0, Optional.empty(), Optional.empty());
        return fixture.service().readSource(admitted, request,
                generation -> { throw new AssertionError("Raw read must not access an index"); });
    }
}
