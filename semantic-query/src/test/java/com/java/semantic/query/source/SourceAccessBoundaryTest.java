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
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        List<String> paths = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        do {
            FileCollection page = fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "", 1, cursor));
            paths.addAll(page.items().stream().map(FileEntry::path).toList());
            cursor = page.page().nextCursor();
        } while (cursor.isPresent());
        assertThat(paths).containsExactly("a.java", "a", "中.java", "🙂.java");
        assertThat(fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "a", 20, Optional.empty()))
                .items()).extracting(FileEntry::path).containsExactly("a/子");
    }

    @Test
    void excluded_traversal_symlinks_and_untracked_files_do_not_become_source() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("safe.java", "safe");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        Files.writeString(fixture.tree.resolve("untracked.java"), "untracked");
        assertThatThrownBy(() -> fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "untracked.java", 1, 1, Optional.empty())))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
        assertThatThrownBy(() -> fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "target/safe.java", 1, 1, Optional.empty())))
                .isInstanceOf(SourceQueryException.class);
        assertThatThrownBy(() -> fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "../safe.java", 1, 1, Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        Files.delete(fixture.tree.resolve("safe.java"));
        Files.createSymbolicLink(fixture.tree.resolve("safe.java"), Path.of("untracked.java"));
        assertThatThrownBy(() -> fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "safe.java", 1, 1, Optional.empty())))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
    }

    @Test
    void cursor_from_other_directory_or_invalid_position_is_not_admitted() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "a");
        fixture.file("b.java", "b");
        fixture.directory("sub");
        fixture.file("sub/c.java", "c");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        FileCollection first = fixture.service().listFiles(admitted, new FileListRequest(fixture.context, "", 1, Optional.empty()));
        assertThatThrownBy(() -> fixture.service().listFiles(admitted, new FileListRequest(fixture.context,
                "sub", 1, first.page().nextCursor()))).isInstanceOf(SourceQueryException.class);
        String binding = com.java.semantic.query.application.QueryCursorCodec.binding("source_files", List.of(
                fixture.context.repositoryId(), fixture.context.revision(), admitted.manifestDigest(), "", "1"));
        String forged = com.java.semantic.query.application.QueryCursorCodec.encode(binding, List.of("ghost.java"));
        assertThatThrownBy(() -> fixture.service().listFiles(admitted, new FileListRequest(fixture.context,
                "", 1, Optional.of(forged)))).isInstanceOf(SourceQueryException.class);
    }
}
