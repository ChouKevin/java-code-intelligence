package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedSourceReaderTest {
    @TempDir Path temp;

    @Test
    void oversized_line_unicode_crlf_and_final_no_lf_reconstruct_exact_bytes() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String original = "中文🙂\r\n" + "x".repeat(70_000) + "🙂\r\nfinal";
        fixture.file("a.java", original);
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        Optional<String> cursor = Optional.empty();
        long previousOffset = -1;
        int pages = 0;
        do {
            SourceResult page = fixture.service().readSource(admitted,
                    new ReadSourceRequest(fixture.context, "a.java", 1, 500, cursor));
            byte[] fragment = page.content().getBytes(StandardCharsets.UTF_8);
            assertThat(fragment.length).isLessThanOrEqualTo(65_536);
            collected.writeBytes(fragment);
            Optional<String> next = page.nextCursor();
            if (page.hasMore()) {
                assertThat(next).isPresent().isNotEqualTo(cursor);
                long position = Long.parseLong(QueryCursorCodec.decode(next.orElseThrow(), QueryCursorCodec.binding("source_read",
                        List.of(fixture.context.repositoryId(), fixture.context.revision(), admitted.manifestDigest(),
                                "a.java", "1", "500", fixture.entries.getFirst().contentDigest().orElseThrow())),
                        2).getFirst());
                assertThat(position).isGreaterThan(previousOffset);
                previousOffset = position;
            }
            cursor = next;
            pages++;
            assertThat(pages).isLessThan(10);
        } while (cursor.isPresent());
        assertThat(collected.toByteArray()).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void consecutive_lf_includes_the_blank_line_without_inventing_an_eof_line() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("blank.java", "foo\n\n");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        SourceResult first = fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "blank.java", 1, 1, Optional.empty()));
        assertThat(first.startLine()).isEqualTo(1);
        assertThat(first.endLine()).contains(1);
        assertThat(first.content()).isEqualTo("foo\n");
        assertThat(first.hasMore()).isTrue();
        SourceResult second = fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "blank.java", 1, 1, first.nextCursor()));
        assertThat(second.startLine()).isEqualTo(2);
        assertThat(second.endLine()).contains(2);
        assertThat(second.content()).isEqualTo("\n");
        assertThat(second.hasMore()).isFalse();
        SourceResult together = fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "blank.java", 1, 3, Optional.empty()));
        assertThat(together.content()).isEqualTo(first.content() + second.content());
        assertThat(together.endLine()).contains(2);
        assertThat(together.nextCursor()).isEmpty();
    }

    @Test
    void empty_and_beyond_eof_have_no_fake_range_and_forged_offset_is_rejected() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("empty.java", "");
        fixture.file("text.java", "🙂\n");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        SourceResult empty = fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "empty.java", 1, 2, Optional.empty()));
        assertThat(empty.content()).isEmpty();
        assertThat(empty.endLine()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
        SourceResult beyond = fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "text.java", 20, 2, Optional.empty()));
        assertThat(beyond.content()).isEmpty();
        assertThat(beyond.endLine()).isEmpty();
        String digest = fixture.entries.stream().filter(entry -> entry.path().equals("text.java"))
                .findFirst().orElseThrow().contentDigest().orElseThrow();
        String binding = QueryCursorCodec.binding("source_read", List.of(fixture.context.repositoryId(),
                fixture.context.revision(), admitted.manifestDigest(), "text.java", "1", "2", digest));
        String forged = QueryCursorCodec.encode(binding, List.of("2", "9"));
        assertThatThrownBy(() -> fixture.service().readSource(admitted,
                new ReadSourceRequest(fixture.context, "text.java", 1, 2, Optional.of(forged))))
                .isInstanceOfSatisfying(SourceQueryException.class, error -> assertThat(error.code())
                        .isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
    }
    @Test
    void nested_json_wire_budget_keeps_control_characters_lossless() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String original = "\u0001".repeat(60_000) + "结束🙂";
        fixture.file("controls.java", original);
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        Optional<String> cursor = Optional.empty();
        StringBuilder reconstructed = new StringBuilder();
        do {
            SourceResult page = fixture.service().readSource(admitted,
                    new ReadSourceRequest(fixture.context, "controls.java", 1, 500, cursor));
            String structured = fixture.mapper.writeValueAsString(page);
            String text = fixture.mapper.writeValueAsString(structured);
            assertThat(structured.getBytes(StandardCharsets.UTF_8).length
                    + text.getBytes(StandardCharsets.UTF_8).length).isLessThan(524_288);
            reconstructed.append(page.content());
            cursor = page.nextCursor();
        } while (cursor.isPresent());
        assertThat(reconstructed.toString()).isEqualTo(original);
    }

    @Test
    void four_byte_budget_advances_over_emoji_and_exact_eof() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("emoji.java", "🙂🙂");
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        SourceAccessProperties small = new SourceAccessProperties(fixture.root, fixture.properties.rgExecutable(),
                List.of("sample"), 4, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        LocalRepositorySourceService service = new LocalRepositorySourceService(small, fixture.mapper);
        SourceResult first = service.readSource(admitted,
                new ReadSourceRequest(fixture.context, "emoji.java", 1, 1, Optional.empty()));
        assertThat(first.content()).isEqualTo("🙂");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.endsMidLine()).isTrue();
        SourceResult last = service.readSource(admitted,
                new ReadSourceRequest(fixture.context, "emoji.java", 1, 1, first.nextCursor()));
        assertThat(last.content()).isEqualTo("🙂");
        assertThat(last.startsMidLine()).isTrue();
        assertThat(last.hasMore()).isFalse();
    }

    @Test
    void quote_heavy_relative_path_does_not_overflow_nested_wire_metadata() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String path = String.join("/", java.util.Collections.nCopies(18, "\"".repeat(200))) + "/a.java";
        String original = "\u0001".repeat(50_000);
        String directory = "";
        for (int index = 0; index < 18; index++) {
            directory = directory.isEmpty() ? "\"".repeat(200) : directory + "/" + "\"".repeat(200);
            fixture.directory(directory);
        }
        fixture.file(path, original);
        AdmittedSourceRevision admitted = fixture.publish(Optional.empty());
        StringBuilder rebuilt = new StringBuilder();
        Optional<String> cursor = Optional.empty();
        do {
            SourceResult page = fixture.service().readSource(admitted,
                    new ReadSourceRequest(fixture.context, path, 1, 500, cursor));
            String structured = fixture.mapper.writeValueAsString(page);
            assertThat(structured.getBytes(StandardCharsets.UTF_8).length
                    + fixture.mapper.writeValueAsBytes(structured).length).isLessThanOrEqualTo(524_288);
            rebuilt.append(page.content());
            cursor = page.nextCursor();
        } while (cursor.isPresent());
        assertThat(rebuilt.toString()).isEqualTo(original);
    }

}
