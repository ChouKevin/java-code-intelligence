package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceReadContract.ChildPreview;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileEntry;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.query.application.QueryCursorCodec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedDirectoryReaderTest {
    @TempDir Path temp;

    @Test
    void previews_thirteen_children_and_resumes_inside_preview_without_leaking_deeper_paths() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String longUnicodePath = "module/!" + "界".repeat(60) + "🙂.java";
        fixture.directory("module");
        fixture.file(longUnicodePath, "unicode");
        for (int index = 0; index < 10; index++) {
            fixture.file(String.format("module/child-%02d.java", index), "child");
        }
        fixture.directory("module/nested");
        fixture.file("module/nested/deep.java", "must not appear at depth two");
        fixture.file("module/z-omitted.java", "thirteenth child");
        fixture.directory("sibling");
        fixture.file("sibling/readme.txt", "sibling preview");
        fixture.publish(Optional.empty());

        try (AdmittedSourceRevision admitted = fixture.admit()) {
            BoundedDirectoryReader reader = new BoundedDirectoryReader(fixture.properties, fixture.mapper);
            FileListRequest request = new FileListRequest(fixture.context, "", 2, 5, Optional.empty());
            FileCollection first = reader.read(admitted, request);

            assertThat(first.items()).extracting(FileEntry::path).containsExactly("module", longUnicodePath,
                    "module/child-00.java", "module/child-01.java", "module/child-02.java");
            assertThat(first.items().getFirst().depth()).isEqualTo(1);
            assertThat(first.items().getFirst().childPreview()).contains(ChildPreview.ELIDED);
            assertThat(first.page().hasMore()).isTrue();
            assertThat(first.page().nextCursor()).isPresent();

            assertThatThrownBy(() -> reader.read(admitted, new FileListRequest(fixture.context, "", 1, 5,
                    first.page().nextCursor())))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));

            String binding = QueryCursorCodec.binding("source_files", List.of(fixture.context.repositoryId(),
                    fixture.context.revision(), admitted.manifestDigest(), "", "2", "inventory-preorder-12-v1", "5"));
            String forgedPosition = QueryCursorCodec.encode(binding, List.of("module/z-omitted.java"));
            assertThatThrownBy(() -> reader.read(admitted, new FileListRequest(fixture.context, "", 2, 5,
                    Optional.of(forgedPosition))))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));

            FileCollection second = reader.read(admitted, new FileListRequest(fixture.context, "", 2, 5,
                    first.page().nextCursor()));
            assertThat(second.items()).extracting(FileEntry::path).containsExactly("module/child-03.java",
                    "module/child-04.java", "module/child-05.java", "module/child-06.java", "module/child-07.java");
            assertThat(second.items()).noneMatch(item -> item.path().equals("module"));
            assertThat(second.page().hasMore()).isTrue();

            FileCollection third = reader.read(admitted, new FileListRequest(fixture.context, "", 2, 5,
                    second.page().nextCursor()));
            assertThat(third.items()).extracting(FileEntry::path).containsExactly("module/child-08.java",
                    "module/child-09.java", "module/nested", "sibling", "sibling/readme.txt");
            FileEntry nested = third.items().get(2);
            assertThat(nested.kind()).isEqualTo(EntryKind.DIRECTORY);
            assertThat(nested.depth()).isEqualTo(2);
            assertThat(nested.childPreview()).contains(ChildPreview.NOT_EXPANDED);
            assertThat(third.items().get(3).childPreview()).contains(ChildPreview.COMPLETE);
            assertThat(third.page().hasMore()).isFalse();
            assertThat(third.page().nextCursor()).isEmpty();

            List<String> observed = new ArrayList<>();
            observed.addAll(first.items().stream().map(FileEntry::path).toList());
            observed.addAll(second.items().stream().map(FileEntry::path).toList());
            observed.addAll(third.items().stream().map(FileEntry::path).toList());
            assertThat(observed).containsExactly("module", longUnicodePath, "module/child-00.java",
                    "module/child-01.java", "module/child-02.java", "module/child-03.java", "module/child-04.java",
                    "module/child-05.java", "module/child-06.java", "module/child-07.java", "module/child-08.java",
                    "module/child-09.java", "module/nested", "sibling", "sibling/readme.txt");
            assertThat(observed).doesNotContain("module/z-omitted.java", "module/nested/deep.java");

            FileCollection depthOne = reader.read(admitted,
                    new FileListRequest(fixture.context, "", 1, 100, Optional.empty()));
            assertThat(depthOne.items()).extracting(FileEntry::path).containsExactly("module", "sibling");
            assertThat(depthOne.items()).allSatisfy(item -> {
                assertThat(item.depth()).isEqualTo(1);
                assertThat(item.childPreview()).contains(ChildPreview.NOT_EXPANDED);
            });
        }
    }
}
