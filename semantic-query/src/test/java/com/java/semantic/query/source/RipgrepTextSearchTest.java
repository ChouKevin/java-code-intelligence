package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepTextSearchTest {
    @TempDir Path temp;

    @Test
    void real_literal_search_has_unicode_utf16_columns_global_limit_and_guide_hint() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Guide.md", "🙂-literal;$ here\n");
        fixture.file("a.java", "🙂-literal;$ here\n");
        fixture.file("b.java", "🙂-literal;$ here\n");
        AdmittedSourceRevision admitted = fixture.publish(Optional.of("Guide.md"));
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

    @Test
    void glob_directory_and_untracked_physical_content_do_not_bypass_membership() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/One.java", "uniqueA");
        fixture.file("src/Two.md", "uniqueA");
        fixture.file("Other.java", "uniqueA");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        TextSearchResult result = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                "uniqueA", "src", Optional.of("src/*.java"), 20));
        assertThat(result.matches()).extracting(TextMatch::path).containsExactly("src/One.java");
        Files.writeString(fixture.tree.resolve("src/untracked.java"), "uniqueA\n");
        TextSearchResult trackedOnly = fixture.service().searchText(admitted, new TextSearchRequest(fixture.context,
                "uniqueA", "src", Optional.empty(), 20));
        assertThat(trackedOnly.matches()).extracting(TextMatch::path)
                .containsExactlyInAnyOrder("src/One.java", "src/Two.md");
    }

    @Test
    void bounded_child_flood_error_and_deadline_leave_no_live_child() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "needle");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
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

    @Test
    void interruption_reaps_children_and_releases_both_search_slots() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("a.java", "needle");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        Path pid = temp.resolve("active.pids");
        Path executable = script("echo $$ >> '" + pid + "'\nwhile :; do :; done");
        SourceAccessProperties access = new SourceAccessProperties(fixture.root, executable, List.of("sample"),
                65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        LocalRepositorySourceService service = new LocalRepositorySourceService(access, fixture.mapper);
        TextSearchRequest request = new TextSearchRequest(fixture.context, "needle", "", Optional.empty(), 20);
        AtomicReference<SourceQueryException> firstError = new AtomicReference<>();
        AtomicReference<SourceQueryException> secondError = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try { service.searchText(admitted, request); }
            catch (SourceQueryException exception) { firstError.set(exception); }
        });
        Thread second = Thread.ofVirtual().start(() -> {
            try { service.searchText(admitted, request); }
            catch (SourceQueryException exception) { secondError.set(exception); }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            while ((!Files.exists(pid) || Files.readAllLines(pid).size() < 2) && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(Files.readAllLines(pid)).hasSize(2);
            assertThatThrownBy(() -> service.searchText(admitted, request))
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
        assertThatThrownBy(() -> service.searchText(admitted, request))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));
    }

    private Path script(String body) throws Exception {
        Path executable = Files.createTempFile(temp, "rg-process-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body + "\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
