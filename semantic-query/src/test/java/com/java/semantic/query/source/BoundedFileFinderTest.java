package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.application.SemanticQueryFacade;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedFileFinderTest {
    @TempDir Path temp;

    @Test
    void discovery_and_real_native_text_search_select_the_same_unicode_and_alternative_paths() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        List<String> paths = List.of("A.java", "é.java", "😀.java", "a?.java", "[x].java", "!bang.java", "a,b.java",
                "ab.java", "src/A.java", "src/é.java", "src/😀.java");
        for (String path : paths) fixture.file(path, "shared needle\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Map<String, List<String>> expected = Map.of(
                    "?.java", List.of("A.java", "é.java", "😀.java", "src/A.java", "src/é.java", "src/😀.java"),
                    "[é-😀].java", List.of("é.java", "😀.java", "src/é.java", "src/😀.java"),
                    "{a\\,b.java,{?.java,src/*.java}}", List.of("a,b.java", "A.java", "é.java", "😀.java", "src/A.java", "src/é.java", "src/😀.java"),
                    "a\\?.java", List.of("a?.java"),
                    "\\[x\\].java", List.of("[x].java"),
                    "\\!bang.java", List.of("!bang.java"));
            for (Map.Entry<String, List<String>> selection : expected.entrySet()) {
                FileFindResult found = fixture.service().findFiles(admitted, request(fixture, selection.getKey(), "", 100, Optional.empty()));
                TextSearchResult searched = fixture.service().searchText(admitted,
                        new TextSearchRequest(fixture.context, "shared needle", "", Optional.of(selection.getKey()), 100));
                assertThat(found.items()).as(selection.getKey()).extracting(FoundFile::path).containsExactlyInAnyOrderElementsOf(selection.getValue());
                assertThat(searched.matches()).as(selection.getKey()).extracting(TextMatch::path).containsExactlyInAnyOrderElementsOf(selection.getValue());
                assertThat(searched.scanComplete()).isTrue();
            }
        }
    }

    @Test
    void raw_discovery_returns_all_file_statuses_at_any_depth_without_reading_bodies() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.directory("src/deep");
        fixture.file("Guide.md", "untrusted guide\n");
        fixture.file("A.java", "needle\n");
        fixture.file("src/A.java", "needle\n");
        fixture.file("src/deep/B.java", "needle\n");
        fixture.file("pom.xml", "root descriptor\n");
        fixture.file("src/pom.xml", "nested descriptor\n");
        for (EntryStatus status : EntryStatus.values()) {
            if (status != EntryStatus.TEXT) fixture.metadata("statuses/" + status + ".dat", status, 17);
        }
        fixture.publish(Optional.of("Guide.md"));
        Files.writeString(fixture.tree.resolve("untracked.java"), "not published\n");
        Files.createDirectories(fixture.tree.resolve("target"));
        Files.writeString(fixture.tree.resolve("target/Hidden.java"), "excluded\n");
        SemanticQueryFacade facade = facade(fixture);
        FileFindResult result = (FileFindResult) facade.execute("find_files", Map.of("context", Map.of(
                "repositoryId", fixture.context.repositoryId(), "revision", fixture.context.revision()), "filePattern", "**", "limit", 100));
        List<String> expected = fixture.entries.stream().filter(entry -> entry.kind() == EntryKind.FILE).map(entry -> entry.path()).toList();
        assertThat(result.context()).isEqualTo(fixture.context);
        assertThat(result.items()).extracting(FoundFile::path).containsExactlyElementsOf(expected);
        assertThat(result.page()).isEqualTo(new Page(expected.size(), false, Optional.empty()));
        assertThat(result.items()).filteredOn(FoundFile::navigationHint).extracting(FoundFile::path).containsExactly("Guide.md");
        for (EntryStatus status : EntryStatus.values()) {
            if (status == EntryStatus.TEXT) continue;
            assertThat(result.items()).filteredOn(file -> file.status() == status).singleElement().satisfies(file -> {
                assertThat(file.path()).isEqualTo("statuses/" + status + ".dat");
                assertThat(file.byteLength()).isEqualTo(17);
                assertThat(file.navigationHint()).isFalse();
            });
        }
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            for (String pattern : List.of("*.java", "**/*.java")) {
                assertThat(fixture.service().findFiles(admitted, request(fixture, pattern, "", 100, Optional.empty())).items())
                        .extracting(FoundFile::path).containsExactly("A.java", "src/A.java", "src/deep/B.java");
            }
            assertThat(fixture.service().findFiles(admitted, request(fixture, "src/*.java", "", 100, Optional.empty())).items())
                    .extracting(FoundFile::path).containsExactly("src/A.java");
            assertThat(fixture.service().findFiles(admitted, request(fixture, "src/**/*.java", "", 100, Optional.empty())).items())
                    .extracting(FoundFile::path).containsExactly("src/A.java", "src/deep/B.java");
            assertThat(fixture.service().findFiles(admitted, request(fixture, "pom.xml", "src", 20, Optional.empty())).items()).isEmpty();
            assertThat(fixture.service().findFiles(admitted, request(fixture, "**/pom.xml", "src", 20, Optional.empty())).items())
                    .extracting(FoundFile::path).containsExactly("src/pom.xml");
            for (String pattern : List.of("absent.txt", "target/**", "*.class")) {
                FileFindResult empty = fixture.service().findFiles(admitted, request(fixture, pattern, "", 20, Optional.empty()));
                assertThat(empty.items()).isEmpty();
                assertThat(empty.page()).isEqualTo(new Page(0, false, Optional.empty()));
            }
            assertThatThrownBy(() -> fixture.service().findFiles(admitted, request(fixture, "**", "missing", 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_NOT_FOUND));
        }
    }

    @Test
    void continuesPinnedRevisionAfterCurrentAdvance() throws Exception {
        SourceFilesystemFixture a = new SourceFilesystemFixture(temp);
        a.file("A.java", "old\n"); a.directory("src"); a.file("src/B.java", "old\n"); a.file("src/C.java", "old\n");
        a.publish(Optional.empty());
        SemanticQueryFacade facade = facade(a);
        FileFindRequest request = request(a, "*.java", "", 1, Optional.empty());
        FileFindResult first = facade.findFiles(request);
        assertThat(first.items()).extracting(FoundFile::path).containsExactly("A.java");
        SourceFilesystemFixture b = new SourceFilesystemFixture(temp, "b".repeat(40));
        b.file("Different.java", "new\n"); b.publish(Optional.empty());
        assertThat(facade.getContext(new ContextRequest("sample", Optional.empty())).context()).contains(b.context);
        List<String> actual = new ArrayList<>(first.items().stream().map(FoundFile::path).toList());
        FileFindResult page = first;
        while (page.page().hasMore()) {
            page = facade.findFiles(request(a, "*.java", "", 1, page.page().nextCursor()));
            assertThat(page.context()).isEqualTo(a.context);
            actual.addAll(page.items().stream().map(FoundFile::path).toList());
        }
        assertThat(actual).containsExactly("A.java", "src/B.java", "src/C.java");
        assertThat(facade.findFiles(request(b, "*.java", "", 20, Optional.empty())).items()).extracting(FoundFile::path).containsExactly("Different.java");
        Path statePath = a.root.resolve("sample/state.json");
        SourceRepositoryState state = a.mapper.readValue(Files.readAllBytes(statePath), SourceRepositoryState.class);
        SourceRepositoryState withdrawn = new SourceRepositoryState(state.formatVersion(), state.repositoryId(), state.current(),
                Map.of(b.context.revision(), state.published().get(b.context.revision())), state.preparation());
        Files.write(statePath, a.mapper.writeValueAsBytes(withdrawn));
        assertThatThrownBy(() -> facade.findFiles(request(a, "*.java", "", 1, first.page().nextCursor())))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.REVISION_NOT_PREPARED));
    }

    @Test
    void cursors_bind_operation_policy_original_pattern_scope_limit_manifest_and_matching_boundary() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("A.java", "a\nb\n"); fixture.directory("src"); fixture.file("src/B.java", "b\n");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = facade(fixture);
        String cursor = facade.findFiles(request(fixture, "*.java", "", 1, Optional.empty())).page().nextCursor().orElseThrow();
        for (FileFindRequest changed : List.of(request(fixture, "**/*.java", "", 1, Optional.of(cursor)),
                request(fixture, "*.java", "src", 1, Optional.of(cursor)), request(fixture, "*.java", "", 2, Optional.of(cursor)))) invalid(() -> facade.findFiles(changed));
        invalid(() -> facade.findFiles(request(fixture, "*.java", "", 1, Optional.of("!" + cursor.substring(1)))));
        String listCursor = facade.listFiles(new FileListRequest(fixture.context, "", 1, 1, Optional.empty())).page().nextCursor().orElseThrow();
        String readCursor = facade.readSource(new ReadSourceRequest(fixture.context, "A.java", View.RAW, Optional.of(1), List.of(),
                Optional.empty(), 1, 0, 0, Optional.empty(), Optional.empty())).nextCursor().orElseThrow();
        for (String other : List.of(listCursor, readCursor, QueryCursorCodec.encode("index-search", List.of("generation", "position")))) {
            invalid(() -> facade.findFiles(request(fixture, "*.java", "", 1, Optional.of(other))));
        }
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            String binding = QueryCursorCodec.binding("source_find_files", List.of("source-glob-1", fixture.context.repositoryId(),
                    fixture.context.revision(), admitted.manifestDigest(), "*.java", "", "1"));
            String unknownBoundary = SourceReadCursorCodec.encode(binding, List.of("absent.java"));
            invalid(() -> fixture.service().findFiles(admitted, request(fixture, "*.java", "", 1, Optional.of(unknownBoundary))));
            String searchToken = SourceReadCursorCodec.encode(QueryCursorCodec.binding("source_search", List.of()), List.of("A.java"));
            invalid(() -> fixture.service().findFiles(admitted, request(fixture, "*.java", "", 1, Optional.of(searchToken))));
            invalid(() -> fixture.service().findFiles(admitted, new FileFindRequest(new SourceContext("other", fixture.context.revision()), "**", "", 20, Optional.empty())));
        }
        for (SourceContext context : List.of(new SourceContext("other", fixture.context.revision()), new SourceContext("sample", "f".repeat(40)))) {
            assertThatThrownBy(() -> facade.findFiles(new FileFindRequest(context, "**", "", 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(context.repositoryId().equals("other")
                            ? SourceQueryException.Code.REPOSITORY_NOT_FOUND : SourceQueryException.Code.REVISION_NOT_PREPARED));
        }
        invalid(() -> facade.execute("find_files", Map.of("context", Map.of("repositoryId", "sample", "revision", "abc123"), "filePattern", "**")));
        fixture.file("Z.java", "new inventory\n"); fixture.publish(Optional.empty());
        invalid(() -> facade.findFiles(request(fixture, "*.java", "", 1, Optional.of(cursor))));
    }

    @Test
    void byteLimitedPagesPreserveEveryPathAndLateCorruptionFails() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        List<String> paths = List.of("a.txt", "é.txt", "\uE000.txt", "😀.txt", "😀/nested.txt");
        for (String path : paths) fixture.metadata(path, EntryStatus.BINARY, 4);
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = facade(fixture);
        List<String> actual = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        do {
            FileFindResult page = facade.findFiles(request(fixture, "**", "", 2, cursor));
            actual.addAll(page.items().stream().map(FoundFile::path).toList()); cursor = page.page().nextCursor();
        } while (cursor.isPresent());
        assertThat(actual).containsExactly("a.txt", "é.txt", "\uE000.txt", "😀.txt", "😀/nested.txt");
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Files.writeString(fixture.inventory, "corrupt late frame\n", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> fixture.service().findFiles(admitted, request(fixture, "**", "", 1, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test
    void symlink_is_metadata_only_and_directory_symlinks_never_become_scope() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        Path outside = temp.resolve("outside"); Files.createDirectories(outside); Files.writeString(outside.resolve("secret"), "private\n");
        fixture.metadata("link", EntryStatus.SYMLINK, 7); Files.createSymbolicLink(fixture.tree.resolve("link"), outside.resolve("secret"));
        fixture.directory("linked"); Files.createSymbolicLink(fixture.tree.resolve("linked"), outside);
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            assertThat(fixture.service().findFiles(admitted, request(fixture, "link", "", 20, Optional.empty())).items())
                    .containsExactly(new FoundFile("link", EntryStatus.SYMLINK, 7, false));
            assertThatThrownBy(() -> fixture.service().findFiles(admitted, request(fixture, "**", "linked", 20, Optional.empty())))
                    .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE));
        }
    }

    @Test
    void cancelled_and_expired_scans_fail_explicitly_without_poisoning_later_search_capacity() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp); fixture.file("A.java", "a\n"); fixture.publish(Optional.empty());
        SemanticQueryFacade facade = facade(fixture);
        assertThatThrownBy(() -> SourceOperationDeadline.within(Duration.ZERO, () -> facade.findFiles(request(fixture, "**", "", 20, Optional.empty()))))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> fixture.service().findFiles(admitted, request(fixture, "**", "", 20, Optional.empty())))
                        .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
            } finally { Thread.interrupted(); }
        }
        assertThat(facade.findFiles(request(fixture, "**", "", 20, Optional.empty())).items()).extracting(FoundFile::path).containsExactly("A.java");
    }

    private static FileFindRequest request(SourceFilesystemFixture fixture, String pattern, String directory, int limit, Optional<String> cursor) {
        return new FileFindRequest(fixture.context, pattern, directory, limit, cursor);
    }
    private static SemanticQueryFacade facade(SourceFilesystemFixture fixture) {
        return new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index, fixture.properties.maxActiveSearches());
    }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOfAny(IllegalArgumentException.class, SourceQueryException.class)
            .satisfies(error -> { if (error instanceof SourceQueryException source) assertThat(source.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT); }); }
}
