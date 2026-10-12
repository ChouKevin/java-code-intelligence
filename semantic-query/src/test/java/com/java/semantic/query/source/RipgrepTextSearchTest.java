package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepTextSearchTest {
    @TempDir Path temp;

    @Test
    void bare_and_globstar_patterns_include_root_and_nested_files() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("Root.java", "needle\n");
        fixture.file("src/Nested.java", "needle\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            for (String pattern : List.of("*.java", "**/*.java")) {
                TextSearchResult result = fixture.service().searchText(admitted,
                        new TextSearchRequest(fixture.context, "needle", "", Optional.of(pattern), 20));
                assertThat(result.matches()).extracting(TextMatch::path)
                        .containsExactlyInAnyOrder("Root.java", "src/Nested.java");
            }
        }
    }

    @Test
    void unicodeAndNestedAlternativesCannotBeLostByNativePruning() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.directory("src/deep");
        List<String> paths = List.of("A.java", "é.java", "😀.java", "a?.java", "[x].java", "!bang.java",
                "a,b.java", "ab.java", "pom.xml", "src/A.java", "src/é.java", "src/😀.java",
                "src/deep/B.java", "src/pom.xml", "-.range", "..range", "0.range", "1.range");
        for (String path : paths) fixture.file(path, "needle\n");
        fixture.publish(Optional.empty());
        Map<String, List<String>> selections = Map.ofEntries(
                Map.entry("?.java", List.of("A.java", "é.java", "😀.java", "src/A.java", "src/é.java", "src/😀.java", "src/deep/B.java")),
                Map.entry("[é-😀].java", List.of("é.java", "😀.java", "src/é.java", "src/😀.java")),
                Map.entry("[!A-é].java", List.of("😀.java", "src/😀.java")),
                Map.entry("src/**/*.java", List.of("src/A.java", "src/é.java", "src/😀.java", "src/deep/B.java")),
                Map.entry("{?.java,{src/**/*.java,pom.xml}}", List.of("A.java", "é.java", "😀.java", "src/A.java", "src/é.java", "src/😀.java", "src/deep/B.java", "pom.xml")),
                Map.entry("{a\\,b.java,{é,😀}.java}", List.of("a,b.java", "é.java", "😀.java")),
                Map.entry("a\\?.java", List.of("a?.java")),
                Map.entry("\\[x\\].java", List.of("[x].java")),
                Map.entry("\\!bang.java", List.of("!bang.java")),
                Map.entry("[\\--0].range", List.of("-.range", "..range", "0.range")),
                Map.entry("**/pom.xml", List.of("pom.xml", "src/pom.xml")));
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            for (Map.Entry<String, List<String>> selection : selections.entrySet()) {
                SourceGlob.Matcher matcher = SourceGlob.compile(selection.getKey()).matcher(Long.MAX_VALUE);
                assertThat(paths.stream().filter(matcher::matches).toList()).as(selection.getKey())
                        .containsExactlyInAnyOrderElementsOf(selection.getValue());
                TextSearchResult result = fixture.service().searchText(admitted,
                        new TextSearchRequest(fixture.context, "needle", "", Optional.of(selection.getKey()), 100));
                assertThat(result.matches()).as(selection.getKey()).extracting(TextMatch::path)
                        .containsExactlyInAnyOrderElementsOf(selection.getValue());
                assertThat(result.scanComplete()).isTrue();
            }
            for (String pattern : List.of("pom.xml", "**/pom.xml")) {
                TextSearchResult result = fixture.service().searchText(admitted,
                        new TextSearchRequest(fixture.context, "needle", "src", Optional.of(pattern), 20));
                assertThat(result.matches()).extracting(TextMatch::path)
                        .containsExactlyElementsOf(pattern.equals("pom.xml") ? List.of() : List.of("src/pom.xml"));
            }
        }
    }

    @Test
    void native_projection_false_positives_do_not_consume_the_public_limit() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        for (String path : List.of("a.java", "aa.java", "aaa.java", "aaaa.java", "é.java")) fixture.file(path, "needle\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            TextSearchResult result = fixture.service().searchText(admitted,
                    new TextSearchRequest(fixture.context, "needle", "", Optional.of("[é].java"), 1));
            assertThat(result.matches()).extracting(TextMatch::path).containsExactly("é.java");
            assertThat(result.truncated()).isFalse();
            assertThat(result.scanComplete()).isTrue();
        }
    }

    @Test
    void invalid_glob_is_rejected_before_starting_the_native_process() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("A.java", "needle\n");
        fixture.publish(Optional.empty());
        Path executable = temp.resolve("withdrawn-rg");
        Files.copy(fixture.properties.rgExecutable(), executable);
        SourceAccessProperties properties = new SourceAccessProperties(fixture.root, executable,
                List.of("sample"), 65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        LocalRepositorySourceService service = new LocalRepositorySourceService(properties, fixture.mapper);
        Files.delete(executable);
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            for (String pattern : List.of("[abc", "{a}", "src/a**b", "{src,../escape}/*.java", "!*.java")) {
                assertThatThrownBy(() -> service.searchText(admitted,
                        new TextSearchRequest(fixture.context, "needle", "", Optional.of(pattern), 20)))
                        .isInstanceOfSatisfying(SourceQueryException.class,
                                exception -> assertThat(exception.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
            }
        }
    }

    @Test
    void real_literal_search_has_unicode_utf16_columns_global_limit_and_guide_hint() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Guide.md", "🙂-literal;$ here\n");
        fixture.file("a.java", "🙂-literal;$ here\n");
        fixture.file("b.java", "🙂-literal;$ here\n");
        fixture.publish(Optional.of("Guide.md"));
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "-literal;$", "", Optional.empty(), 1));
            assertThat(result.matches()).hasSize(1);
            assertThat(result.matches().getFirst().column()).isEqualTo(3);
            assertThat(result.matches().getFirst().matchedText()).isEqualTo("-literal;$");
            assertThat(result.truncated()).isTrue();
            assertThat(result.scanComplete()).isFalse();
            TextSearchResult guide = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "-literal;$", "", Optional.of("Guide.md"), 20));
            assertThat(guide.matches()).extracting(TextMatch::path).containsExactly("Guide.md");
            assertThat(guide.matches().getFirst().navigationHint()).isTrue();
            TextSearchResult absent = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "does not appear", "", Optional.empty(), 20));
            assertThat(absent.matches()).isEmpty();
            assertThat(absent.scanComplete()).isTrue();
        }
    }

    @Test
    void glob_directory_and_untracked_physical_content_do_not_bypass_membership() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/One.java", "uniqueA");
        fixture.file("src/Two.md", "uniqueA");
        fixture.file("Other.java", "uniqueA");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "uniqueA", "src", Optional.of("src/*.java"), 20));
            assertThat(result.matches()).extracting(TextMatch::path).containsExactly("src/One.java");
            Files.writeString(fixture.tree.resolve("src/untracked.java"), "uniqueA\n");
            TextSearchResult trackedOnly = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "uniqueA", "src", Optional.empty(), 20));
            assertThat(trackedOnly.matches()).extracting(TextMatch::path)
                    .containsExactlyInAnyOrder("src/One.java", "src/Two.md");
        }
    }

    @Test
    void untracked_match_before_authorized_prefix_does_not_consume_global_limit() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("b.java", "needle\n");
        fixture.file("c.java", "needle\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Files.writeString(fixture.tree.resolve("a.java"), "needle\n");
            TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "needle", "", Optional.empty(), 1));
            assertThat(result.matches()).extracting(TextMatch::path).containsExactly("b.java");
            assertThat(result.truncated()).isTrue();
            assertThat(result.scanComplete()).isFalse();
        }
    }

    @Test
    void multiwindow_unknown_prefix_preserves_real_directory_traversal_order() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("a");
        fixture.file("a/z.java", "needle\n");
        fixture.file("a.java", "needle\n");
        fixture.directory("z");
        for (int index = 0; index < 512; index++) {
            fixture.file("z/" + String.format("%04d", index) + ".java", "no match\n");
        }
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            for (int index = 0; index < 12; index++) {
                Files.writeString(fixture.tree.resolve("a/" + String.format("%04d", index) + ".java"), "needle\n");
            }
            TextSearchResult limited = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "needle", "", Optional.empty(), 1));
            assertThat(limited.matches()).extracting(TextMatch::path).containsExactly("a/z.java");
            assertThat(limited.truncated()).isTrue();
            assertThat(limited.scanComplete()).isFalse();
            TextSearchResult complete = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "needle", "", Optional.empty(), 20));
            assertThat(complete.matches()).extracting(TextMatch::path).containsExactly("a/z.java", "a.java");
            assertThat(complete.truncated()).isFalse();
            assertThat(complete.scanComplete()).isTrue();
        }
    }

    @Test
    void verified_inventory_seeks_cover_first_last_unicode_and_missing_paths() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("0-first.java", "first-only\n");
        fixture.directory("a");
        fixture.file("a/nested.java", "nested-only\n");
        fixture.file("\u00e9-last.java", "last-only\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Files.writeString(fixture.tree.resolve("missing.java"), "unknown-only\n");
            TextSearchResult first = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "first-only", "", Optional.empty(), 1));
            assertThat(first.matches()).extracting(TextMatch::path).containsExactly("0-first.java");
            assertThat(first.scanComplete()).isTrue();
            TextSearchResult last = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "last-only", "", Optional.empty(), 1));
            assertThat(last.matches()).extracting(TextMatch::path).containsExactly("\u00e9-last.java");
            assertThat(last.scanComplete()).isTrue();
            TextSearchResult nested = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "nested-only", "a", Optional.empty(), 1));
            assertThat(nested.matches()).extracting(TextMatch::path).containsExactly("a/nested.java");
            assertThat(nested.scanComplete()).isTrue();
            TextSearchResult missing = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "unknown-only", "", Optional.empty(), 1));
            assertThat(missing.matches()).isEmpty();
            assertThat(missing.scanComplete()).isTrue();
        }
    }

    @Test
    void utf8_bom_search_columns_include_the_preserved_source_prefix() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("bom.java", "\uFEFF🙂 needle\r\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "needle", "", Optional.empty(), 20));
            assertThat(result.matches()).singleElement().satisfies(match -> {
                assertThat(match.line()).isEqualTo(1);
                assertThat(match.column()).isEqualTo(5);
                assertThat(match.matchedText()).isEqualTo("needle");
            });
        }
    }

    @Test
    void bounded_search_selects_a_stable_path_ordered_prefix_across_files() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("z.java", "needle\n");
        fixture.file("b.java", "needle\n");
        fixture.file("a.java", "needle\n");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                    "needle", "", Optional.empty(), 2));
            assertThat(result.matches()).extracting(TextMatch::path).containsExactly("a.java", "b.java");
            assertThat(result.truncated()).isTrue();
            assertThat(result.scanComplete()).isFalse();
        }
    }

    @Test
    void bounded_child_flood_error_and_deadline_leave_no_live_child() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "needle");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Path pid = temp.resolve("child.pid");
            TextSearchRequest request = new TextSearchRequest(fixture.context, "needle", "", Optional.empty(), 20);
            for (String body : List.of("printf 'fatal\\n' >&2; exit 2",
                    "while :; do printf '012345678901234567890123456789'; done",
                    "while :; do printf '012345678901234567890123456789' >&2; done",
                    "while :; do :; done")) {
                Path executable = script("echo $$ > '" + pid + "'\n" + body);
                SourceAccessProperties access = new SourceAccessProperties(fixture.root, executable, List.of("sample"),
                        65_536, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
                LocalRepositorySourceService service = new LocalRepositorySourceService(access, fixture.mapper);
                assertThatThrownBy(() -> service.searchText(admitted, request)).isInstanceOf(SourceQueryException.class);
                long child = Long.parseLong(Files.readString(pid).trim());
                assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                Files.delete(pid);
            }
        }
    }

    @Test
    void interruption_reaps_children_and_releases_both_search_slots() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "needle");
        fixture.publish(Optional.empty());
        Path pid = temp.resolve("active.pids");
        Path executable = script("echo $$ >> '" + pid + "'\nwhile :; do :; done");
        SourceAccessProperties access = new SourceAccessProperties(fixture.root, executable, List.of("sample"),
                65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(),
                new LocalRepositorySourceService(access, fixture.mapper), fixture.index, access.maxActiveSearches());
        TextSearchRequest request = new TextSearchRequest(fixture.context, "needle", "", Optional.empty(), 20);
        AtomicReference<SourceQueryException> firstError = new AtomicReference<>();
        AtomicReference<SourceQueryException> secondError = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try { facade.searchText(request); }
            catch (SourceQueryException exception) { firstError.set(exception); }
        });
        Thread second = Thread.ofVirtual().start(() -> {
            try { facade.searchText(request); }
            catch (SourceQueryException exception) { secondError.set(exception); }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            while ((!Files.exists(pid) || Files.readAllLines(pid).size() < 2) && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(Files.readAllLines(pid)).hasSize(2);
            assertThatThrownBy(() -> facade.searchText(request))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_BUSY));
        } finally {
            first.interrupt(); second.interrupt();
            first.join(3000); second.join(3000);
        }
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(firstError.get().code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT);
        assertThat(secondError.get().code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT);
        for (String line : Files.readAllLines(pid)) {
            long child = Long.parseLong(line.trim());
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
        assertThatThrownBy(() -> facade.searchText(request))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
    }

    @Test
    void timed_out_search_does_not_wait_on_a_stubborn_parent_and_inherited_descendant_pipes() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "needle");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision admitted = fixture.admit()) {
            Path marker = temp.resolve("spawn-once");
            Path ready = temp.resolve("descendant-ready");
            Path parentPid = temp.resolve("stubborn-parent.pid");
            Path childPid = temp.resolve("pipe-holder.pid");
            Path printed = temp.resolve("partial-frame-printed");
            Files.createFile(marker);
            Path executable = script("if [ -e '" + marker + "' ]; then\n"
                    + "  /bin/rm '" + marker + "'\n"
                    + "  trap '' TERM\n"
                    + "  echo $$ > '" + parentPid + "'\n"
                    + "  /bin/sh -c 'trap \"\" TERM; : > \"" + ready + "\"; while :; do :; done' &\n"
                    + "  echo $! > '" + childPid + "'\n"
                    + "  while [ ! -e '" + ready + "' ]; do :; done\n"
                    + "  printf '{\"type\":\"match\"'\n"
                    + "  : > '" + printed + "'\n"
                    + "  while :; do :; done\n"
                    + "fi\nexit 1");
            SourceAccessProperties access = new SourceAccessProperties(fixture.root, executable, List.of("sample"),
                    65_536, Duration.ofMillis(700), Duration.ofSeconds(2), Duration.ofSeconds(2), 1);
            LocalRepositorySourceService service = new LocalRepositorySourceService(access, fixture.mapper);
            TextSearchRequest request = new TextSearchRequest(fixture.context, "needle", "", Optional.empty(), 20);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            long started = System.nanoTime();
            Thread search = Thread.ofVirtual().start(() -> {
                try { service.searchText(admitted, request); }
                catch (Throwable exception) { outcome.set(exception); }
                finally { completed.countDown(); }
            });
            boolean returned;
            long elapsed = Long.MAX_VALUE;
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((!Files.exists(ready) || !Files.exists(printed) || !Files.exists(childPid)
                        || !Files.exists(parentPid)) && System.nanoTime() < until) Thread.sleep(10);
                assertThat(Files.exists(ready)).isTrue();
                assertThat(Files.exists(printed)).isTrue();
                assertThat(Files.exists(childPid)).isTrue();
                returned = completed.await(2, TimeUnit.SECONDS);
                elapsed = System.nanoTime() - started;
                if (returned) {
                    assertThat(recordedAlive(parentPid)).as("runner reaps its direct child before returning").isFalse();
                    assertThat(recordedAlive(childPid)).as("runner terminates inherited-pipe descendant before returning").isFalse();
                }
            } finally {
                // This test owns both PIDs, including on RED; never leave an inherited pipe open in Maven.
                try { terminateRecorded(childPid); }
                finally {
                    terminateRecorded(parentPid);
                    search.interrupt();
                    search.join(2000);
                }
            }
            assertThat(returned).as("timeout must not wait forever on inherited stdout/stderr").isTrue();
            assertThat(elapsed).as("cleanup belongs to the 700ms operation, not a separate two-second grace")
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(1000));
            assertThat(search.isAlive()).isFalse();
            assertThat(outcome.get()).isInstanceOfSatisfying(SourceQueryException.class,
                    error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
            TextSearchResult next = service.searchText(admitted, request);
            assertThat(next.matches()).isEmpty();
            assertThat(next.scanComplete()).isTrue();
        }
    }

    private static boolean recordedAlive(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void terminateRecorded(Path pidFile) throws Exception {
        if (!Files.exists(pidFile)) return;
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }

    private Path script(String body) throws Exception {
        Path executable = Files.createTempFile(temp, "rg-process-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body + "\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
