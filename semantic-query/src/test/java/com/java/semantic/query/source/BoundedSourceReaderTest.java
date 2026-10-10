package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.SemanticQueryInput;
import com.java.semantic.query.application.QueryCursorCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.java.semantic.query.config.SourceAccessProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedSourceReaderTest {
    @TempDir Path temp;

    @Test
    void huge_unicode_crlf_and_final_no_lf_reconstruct_lossless_bounded_segments() throws Exception {
        SourceFilesystemFixture fixture = fixture("a.java", "中文🙂\r\n" + "x".repeat(70_000) + "🙂\r\nfinal");
        String original = "中文🙂\r\n" + "x".repeat(70_000) + "🙂\r\nfinal";
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> pages = collect(fixture, source, Map.of("path", "a.java", "view", "raw", "maxLines", 1), noSql());
            assertThat(text(pages)).isEqualTo(original);
            assertThat(pages).hasSizeGreaterThan(3);
            long next = 0;
            for (SourceResult page : pages) {
                for (Segment segment : page.segments()) {
                    assertThat(segment.startByte()).isEqualTo(next);
                    assertThat(segment.content().orElseThrow().getBytes(StandardCharsets.UTF_8))
                            .isEqualTo(java.util.Arrays.copyOfRange(original.getBytes(StandardCharsets.UTF_8), (int) segment.startByte(), (int) segment.endByte()));
                    next = segment.endByte();
                }
                assertThat(page.effectiveView()).isEqualTo(View.RAW);
                assertThat(page.segments().stream().mapToInt(segment -> segment.content().orElse("").getBytes(StandardCharsets.UTF_8).length).sum()).isLessThanOrEqualTo(65_536);
            }
            assertThat(next).isEqualTo(original.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void overlapping_and_disjoint_ranges_merge_without_duplicate_bytes_or_outside_continuation() throws Exception {
        SourceFilesystemFixture fixture = fixture("ranges.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n");
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> pages = collect(fixture, source, Map.of("path", "ranges.txt", "view", "raw", "maxLines", 2,
                    "ranges", List.of(Map.of("startLine", 7, "endLine", 8), Map.of("startLine", 2, "endLine", 3), Map.of("startLine", 3, "endLine", 4))), noSql());
            assertThat(text(pages)).isEqualTo("2\n3\n4\n7\n8\n");
            assertThat(pages.getFirst().effectiveSelection()).containsExactly(new LineRange(2, 4), new LineRange(7, 8));
            assertThat(pages.getLast().hasMore()).isFalse();
            List<SourceResult> context = collect(fixture, source, Map.of("path", "ranges.txt", "ranges", List.of(Map.of("startLine", 3, "endLine", 3))), noSql());
            assertThat(text(context)).isEqualTo("2\n3\n4\n5\n6\n");
        }
    }

    @Test
    void tail_obeys_final_lf_no_lf_and_empty_file_line_rules() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("lf.txt", "one\n\nlast\n");
        fixture.file("no-lf.txt", "one\n\nlast");
        fixture.file("empty.txt", "");
        fixture.publish(Optional.empty());
        try (AdmittedSourceRevision source = fixture.admit()) {
            assertThat(text(collect(fixture, source, Map.of("path", "lf.txt", "tailLines", 2), noSql()))).isEqualTo("\nlast\n");
            assertThat(text(collect(fixture, source, Map.of("path", "no-lf.txt", "tailLines", 2), noSql()))).isEqualTo("\nlast");
            SourceResult empty = collect(fixture, source, Map.of("path", "empty.txt", "tailLines", 2), noSql()).getFirst();
            assertThat(empty.segments()).isEmpty();
            assertThat(empty.effectiveSelection()).isEmpty();
            SourceResult outside = collect(fixture, source, Map.of("path", "lf.txt", "startLine", 20), noSql()).getFirst();
            assertThat(outside.segments()).isEmpty();
            assertThat(outside.hasMore()).isFalse();
        }
    }

    @Test
    void summary_preserves_brace_lines_and_pins_generation_across_pages() throws Exception {
        String original = "class A {\n void run() {\n  a();\n  b();\n  c();\n  d();\n }\n}\n";
        SourceFilesystemFixture fixture = fixture("A.java", original);
        String generation = "00000000-0000-0000-0000-000000000001";
        IndexContract.FoldResult metadata = foldResult(fixture, generation, IndexContract.ParseStatus.PARSED, true,
                List.of(new IndexContract.Fold("b".repeat(64), "METHOD", new IndexContract.SourceSpan(original.indexOf("{", original.indexOf("run")) + 1,
                        original.indexOf(" }"), 2, 7))));
        List<Optional<String>> pins = new ArrayList<>();
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> pages = collect(fixture, source, Map.of("path", "A.java", "view", "summary", "maxLines", 1), pin -> {
                pins.add(pin); return metadata;
            });
            assertThat(text(pages)).isEqualTo("class A {\n void run() {\n }\n}\n");
            Segment elision = pages.stream().flatMap(page -> page.segments().stream()).filter(segment -> segment.kind() == SegmentKind.ELISION).findFirst().orElseThrow();
            assertThat(elision.startLine()).isEqualTo(3);
            assertThat(elision.endLine()).isEqualTo(6);
            assertThat(original.getBytes(StandardCharsets.UTF_8)).containsSubsequence("  a();\n  b();\n  c();\n  d();\n".getBytes(StandardCharsets.UTF_8));
            assertThat(pins.getFirst()).isEmpty();
            assertThat(pins.subList(1, pins.size())).allSatisfy(pin -> assertThat(pin).contains(generation));
            assertThat(pages).allSatisfy(page -> { assertThat(page.effectiveView()).isEqualTo(View.SUMMARY); assertThat(page.indexGeneration()).contains(generation); });
        }
    }

    @Test
    void unavailable_old_and_parse_failed_metadata_are_explicit_and_raw_never_looks_up_sql() throws Exception {
        SourceFilesystemFixture fixture = fixture("A.java", "line\n".repeat(110));
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> auto = collect(fixture, source, Map.of("path", "A.java", "maxLines", 1), pin -> { throw new IndexFailure(IndexFailure.Code.INDEX_NOT_PREPARED); });
            assertThat(auto).allSatisfy(page -> { assertThat(page.effectiveView()).isEqualTo(View.RAW); assertThat(page.summaryOutcome()).isEqualTo(SummaryOutcome.INDEX_NOT_READY); });
            assertThatThrownBy(() -> collect(fixture, source, Map.of("path", "A.java", "view", "summary"),
                    pin -> { throw new IndexFailure(IndexFailure.Code.INDEX_NOT_PREPARED); }))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            failure -> assertThat(failure.reason()).contains(SummaryOutcome.INDEX_NOT_READY));
            for (IndexContract.FoldResult metadata : List.of(
                    foldResult(fixture, "00000000-0000-0000-0000-000000000001", IndexContract.ParseStatus.PARSED, false, List.of()),
                    foldResult(fixture, "00000000-0000-0000-0000-000000000001", IndexContract.ParseStatus.PARSE_FAILED, true, List.of()))) {
                assertThatThrownBy(() -> collect(fixture, source, Map.of("path", "A.java", "view", "summary"), pin -> metadata))
                        .isInstanceOfSatisfying(SourceQueryException.class, failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_SUMMARY_UNAVAILABLE));
            }
            assertThat(text(collect(fixture, source, Map.of("path", "A.java", "view", "raw"), noSql()))).isEqualTo("line\n".repeat(110));
        }
    }

    @Test
    void actual_mcp_serialization_bounds_control_heavy_content_and_metadata() throws Exception {
        String original = "\u0001".repeat(60_000) + "结束🙂";
        SourceFilesystemFixture fixture = fixture("controls.txt", original);
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> pages = collect(fixture, source, Map.of("path", "controls.txt", "view", "raw"), noSql());
            for (SourceResult page : pages) {
                byte[] wire = fixture.mapper.writeValueAsBytes(Map.of("content", List.of(Map.of("type", "text", "text", fixture.mapper.writeValueAsString(page))), "structuredContent", page, "isError", false));
                assertThat(wire.length).isLessThanOrEqualTo(524_288);
                assertThat(page.segments()).isNotEmpty();
            }
            assertThat(text(pages)).isEqualTo(original);
        }
    }

    @Test
    void four_byte_budget_advances_partial_unicode_line_and_cursor_cannot_switch_selection() throws Exception {
        SourceFilesystemFixture fixture = fixture("emoji.txt", "🙂🙂");
        SourceAccessProperties small = new SourceAccessProperties(fixture.root, fixture.properties.rgExecutable(), List.of("sample"), 4,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        BoundedSourceReader reader = new BoundedSourceReader(small, new SourcePathResolver(fixture.mapper), fixture.mapper);
        try (AdmittedSourceRevision source = fixture.admit()) {
            ReadSourceRequest request = request(fixture, Map.of("path", "emoji.txt", "view", "raw"));
            SourceResult first = reader.read(source, request, noSql());
            assertThat(first.segments().getFirst().endsMidLine()).isTrue();
            SourceResult last = reader.read(source, request(fixture, Map.of("path", "emoji.txt", "view", "raw", "cursor", first.nextCursor().orElseThrow())), noSql());
            assertThat(last.segments().getFirst().startsMidLine()).isTrue();
            assertThat(last.hasMore()).isFalse();
            assertThat(text(List.of(first, last))).isEqualTo("🙂🙂");
            assertThatThrownBy(() -> reader.read(source, request(fixture, Map.of("path", "emoji.txt", "view", "raw", "startLine", 2, "cursor", first.nextCursor().orElseThrow())), noSql()))
                    .isInstanceOf(SourceQueryException.class);
        }
    }

    @Test
    void closing_brace_at_column_one_preserves_its_line_and_expired_continuation_does_not_fallback() throws Exception {
        String original = "class A {\nvoid run() {\n a();\n b();\n c();\n d();\n}\n}\n";
        SourceFilesystemFixture fixture = fixture("A.java", original);
        String generation = "00000000-0000-0000-0000-000000000001";
        IndexContract.FoldResult metadata = foldResult(fixture, generation, IndexContract.ParseStatus.PARSED, true,
                List.of(new IndexContract.Fold("b".repeat(64), "METHOD",
                        new IndexContract.SourceSpan(original.indexOf("{", original.indexOf("run")) + 1, original.indexOf("}\n"), 2, 6))));
        try (AdmittedSourceRevision source = fixture.admit()) {
            List<SourceResult> pages = collect(fixture, source, Map.of("path", "A.java", "view", "summary"), pin -> metadata);
            assertThat(text(pages)).isEqualTo("class A {\nvoid run() {\n}\n}\n");
            Segment elision = pages.getFirst().segments().stream().filter(segment -> segment.kind() == SegmentKind.ELISION).findFirst().orElseThrow();
            assertThat(elision.endLine()).isEqualTo(6);
            BoundedSourceReader reader = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
            SourceResult first = reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary", "maxLines", 1)), pin -> metadata);
            assertThatThrownBy(() -> reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary", "maxLines", 1,
                    "cursor", first.nextCursor().orElseThrow())), pin -> { throw new IndexFailure(IndexFailure.Code.INDEX_GENERATION_EXPIRED); }))
                    .isInstanceOfSatisfying(IndexFailure.class, failure -> assertThat(failure.code()).isEqualTo(IndexFailure.Code.INDEX_GENERATION_EXPIRED));
        }
    }

    @Test
    void explicit_pin_rejects_g2_state_with_g1_binding_before_fold_lookup() throws Exception {
        SourceFilesystemFixture fixture = fixture("A.java", "class A {\n void run() {\n  a();\n  b();\n  c();\n  d();\n }\n}\n");
        String g1 = "00000000-0000-0000-0000-000000000001";
        String g2 = "00000000-0000-0000-0000-000000000002";
        BoundedSourceReader reader = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        try (AdmittedSourceRevision source = fixture.admit()) {
            SourceResult firstG1 = reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary",
                    "maxLines", 1, "indexGeneration", g1)),
                    pin -> foldResult(fixture, g1, IndexContract.ParseStatus.PARSED, true, List.of()));
            SourceResult firstG2 = reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary",
                    "maxLines", 1, "indexGeneration", g2)),
                    pin -> foldResult(fixture, g2, IndexContract.ParseStatus.PARSED, true, List.of()));
            String g1Binding = cursorBinding(firstG1.nextCursor().orElseThrow());
            String g2Token = firstG2.nextCursor().orElseThrow();
            List<String> g2State = QueryCursorCodec.decode(payload(g2Token), cursorBinding(g2Token), 7);
            String craftedPayload = QueryCursorCodec.encode(g1Binding, g2State);
            String forged = craftedPayload + g2Token.substring(g2Token.lastIndexOf('.'));
            assertThatThrownBy(() -> reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary",
                    "maxLines", 1, "indexGeneration", g1, "cursor", forged)), noSql()))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
            // Even authenticated contradictory state must not trigger any G2 lookup.
            String contradictory = SourceReadCursorCodec.encode(g1Binding, g2State);
            assertThatThrownBy(() -> reader.read(source, request(fixture, Map.of("path", "A.java", "view", "summary",
                    "maxLines", 1, "indexGeneration", g1, "cursor", contradictory)), noSql()))
                    .isInstanceOfSatisfying(SourceQueryException.class,
                            failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
        }
    }

    @Test
    void bare_auto_rejects_effective_view_generation_profile_and_outcome_tampering() throws Exception {
        String original = "class A {\n void run() {\n" + "  a();\n".repeat(105) + " }\n}\n";
        SourceFilesystemFixture fixture = fixture("A.java", original);
        String generation = "00000000-0000-0000-0000-000000000001";
        IndexContract.FoldResult metadata = foldResult(fixture, generation, IndexContract.ParseStatus.PARSED, true,
                List.of(new IndexContract.Fold("b".repeat(64), "METHOD",
                        new IndexContract.SourceSpan(original.indexOf("{", original.indexOf("run")) + 1,
                                original.indexOf(" }"), 2, 108))));
        BoundedSourceReader reader = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        try (AdmittedSourceRevision source = fixture.admit()) {
            SourceResult first = reader.read(source, request(fixture, Map.of("path", "A.java", "maxLines", 1)), pin -> metadata);
            String token = first.nextCursor().orElseThrow();
            String binding = cursorBinding(token);
            List<String> state = QueryCursorCodec.decode(payload(token), binding, 7);
            for (Map.Entry<Integer, String> change : Map.of(0, "RAW", 1, "00000000-0000-0000-0000-000000000002",
                    2, "INDEX_NOT_READY", 5, "different-profile").entrySet()) {
                List<String> tampered = new ArrayList<>(state);
                tampered.set(change.getKey(), change.getValue());
                String forged = QueryCursorCodec.encode(binding, tampered) + token.substring(token.lastIndexOf('.'));
                assertThatThrownBy(() -> reader.read(source, request(fixture,
                        Map.of("path", "A.java", "maxLines", 1, "cursor", forged)), noSql()))
                        .isInstanceOfSatisfying(SourceQueryException.class,
                                failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
            }
            BoundedSourceReader another = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
            SourceResult continued = another.read(source, request(fixture,
                    Map.of("path", "A.java", "maxLines", 1, "cursor", token)), pin -> {
                assertThat(pin).contains(generation);
                return metadata;
            });
            assertThat(continued.effectiveView()).isEqualTo(View.SUMMARY);
            assertThat(continued.indexGeneration()).contains(generation);
            assertThat(continued.segments().getFirst().content()).contains(" void run() {\n");
        }
    }

    @Test
    void auto_raw_continuation_survives_reader_recreation_without_reconsidering_index() throws Exception {
        SourceFilesystemFixture fixture = fixture("A.java", "line\r\n".repeat(110));
        try (AdmittedSourceRevision source = fixture.admit()) {
            BoundedSourceReader reader = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
            SourceResult first = reader.read(source, request(fixture, Map.of("path", "A.java", "maxLines", 1)),
                    pin -> { throw new IndexFailure(IndexFailure.Code.INDEX_NOT_PREPARED); });
            List<SourceResult> pages = new ArrayList<>(List.of(first));
            while (pages.getLast().hasMore()) {
                assertThat(pages.size()).isLessThan(200);
                BoundedSourceReader another = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
                pages.add(another.read(source, request(fixture, Map.of("path", "A.java", "maxLines", 1,
                        "cursor", pages.getLast().nextCursor().orElseThrow())), noSql()));
            }
            assertThat(text(pages)).isEqualTo("line\r\n".repeat(110));
            assertThat(pages).allSatisfy(page -> assertThat(page.effectiveView()).isEqualTo(View.RAW));
            String token = first.nextCursor().orElseThrow();
            Mac foreignProcess = Mac.getInstance("HmacSHA256");
            foreignProcess.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            String foreignToken = payload(token) + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(foreignProcess.doFinal(payload(token).getBytes(StandardCharsets.US_ASCII)));
            // A token signed by a different process key cannot resume or retarget this read.
            for (String malformed : List.of("x".repeat(65_537), "not-a-token", payload(token), foreignToken)) {
                assertThatThrownBy(() -> reader.read(source, request(fixture,
                        Map.of("path", "A.java", "maxLines", 1, "cursor", malformed)), noSql()))
                        .isInstanceOfSatisfying(SourceQueryException.class,
                                failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
            }
        }
    }

    private static String payload(String token) {
        return token.substring(0, token.lastIndexOf('.'));
    }

    private static String cursorBinding(String token) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(payload(token))))) {
            input.readInt();
            int length = input.readInt();
            StringBuilder binding = new StringBuilder(length);
            for (int index = 0; index < length; index++) binding.append(input.readChar());
            return binding.toString();
        }
    }

    private SourceFilesystemFixture fixture(String path, String content) throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file(path, content); fixture.publish(Optional.empty()); return fixture;
    }
    private static ReadSourceRequest request(SourceFilesystemFixture fixture, Map<String, Object> fields) {
        Map<String, Object> input = new java.util.HashMap<>(fields);
        input.put("context", Map.of("repositoryId", fixture.context.repositoryId(), "revision", fixture.context.revision()));
        return SemanticQueryInput.readSource(input);
    }
    private static List<SourceResult> collect(SourceFilesystemFixture fixture, AdmittedSourceRevision source,
            Map<String, Object> fields, Function<Optional<String>, IndexContract.FoldResult> folds) {
        BoundedSourceReader reader = new BoundedSourceReader(fixture.properties, new SourcePathResolver(fixture.mapper), fixture.mapper);
        Map<String, Object> input = new java.util.HashMap<>(fields);
        List<SourceResult> pages = new ArrayList<>();
        do {
            SourceResult page = reader.read(source, request(fixture, input), folds);
            pages.add(page);
            assertThat(pages.size()).isLessThan(200);
            if (!page.hasMore()) return pages;
            assertThat(page.nextCursor()).isPresent();
            assertThat(page.nextCursor().orElseThrow()).isNotEqualTo(input.get("cursor"));
            input.put("cursor", page.nextCursor().orElseThrow());
        } while (true);
    }
    private static String text(List<SourceResult> pages) {
        return pages.stream().flatMap(page -> page.segments().stream()).map(segment -> segment.content().orElse("")).collect(java.util.stream.Collectors.joining());
    }
    private static Function<Optional<String>, IndexContract.FoldResult> noSql() {
        return pin -> { throw new AssertionError("raw/selected read must not access index"); };
    }
    private static IndexContract.FoldResult foldResult(SourceFilesystemFixture fixture, String generation,
            IndexContract.ParseStatus status, boolean capable, List<IndexContract.Fold> folds) {
        com.java.semantic.model.source.SourceInventoryEntry entry = fixture.entries.getFirst();
        return new IndexContract.FoldResult(generation, capable ? IndexContract.syntaxProfile()
                : new IndexContract.Profile(1, 1, "JavaParser", "3.28.2", "JAVA_21", true, 1),
                new IndexContract.FileOutcome(entry.path(), true, status, entry.byteLength(), entry.blobId(), entry.contentDigest(), List.of()), capable, folds);
    }
}
