package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract.LineRange;
import com.java.semantic.model.source.SourceReadContract.Segment;
import com.java.semantic.model.source.SourceReadContract.SegmentKind;
import com.java.semantic.model.source.SourceReadContract.Selection;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.StopReason;
import com.java.semantic.model.source.SourceReadContract.SummaryOutcome;
import com.java.semantic.model.source.SourceReadContract.View;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SourceReadTextTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String DIGEST = "a".repeat(64);
    private static final String GENERATION = "00000000-0000-0000-0000-000000000001";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void keeps_original_unicode_crlf_blank_and_disjoint_line_coordinates() throws Exception {
        String path = "src/字 \"quoted\".java";
        String firstSelection = "α\r\n\n";
        String omittedLine = "skipped\r\n";
        String lastLine = "final";
        String original = firstSelection + omittedLine + lastLine;
        byte[] originalBytes = original.getBytes(StandardCharsets.UTF_8);
        long omittedStart = (firstSelection + omittedLine).getBytes(StandardCharsets.UTF_8).length;
        List<LineRange> ranges = List.of(new LineRange(1, 2), new LineRange(4, 4));
        Selection selection = new Selection(Optional.empty(), ranges, Optional.empty(), 0, 0);
        List<Segment> segments = List.of(
                sourceSegment(0, firstSelection.getBytes(StandardCharsets.UTF_8).length, 1, 2,
                        firstSelection, false, false),
                sourceSegment(omittedStart, originalBytes.length, 4, 4, lastLine, false, false));
        SourceResult result = rawResult(path, originalBytes.length, selection, ranges, segments, true,
                Optional.of("opaque-next-page"), StopReason.CONTENT_BUDGET, true);

        String text = SourceReadText.render(result, mapper);

        assertThat(text).contains("sample", SHA, mapper.writeValueAsString(path));
        assertThat(text).contains(
                mapper.writeValueAsString("sample@" + SHA + ":" + path + ":L1-L2"),
                mapper.writeValueAsString("sample@" + SHA + ":" + path + ":L4-L4"),
                "bytes=[0,5)", "bytes=[14,19)", "lines=L1-L2", "lines=L4-L4");
        assertThat(text).contains("1 | α\\r", "2 | \n", "4 | final")
                .doesNotContain("3 | ", "5 | ");
        assertThat(result.segments().getFirst().content()).contains(firstSelection);

        JsonNode metadata = metadata(text);
        assertThat(metadata.get("blobId").asString()).isEqualTo(SHA);
        assertThat(metadata.get("contentDigest").asString()).isEqualTo(DIGEST);
        assertThat(metadata.get("byteLength").asLong()).isEqualTo(originalBytes.length);
        assertThat(metadata.get("requestedView").asString()).isEqualTo("RAW");
        assertThat(metadata.get("effectiveView").asString()).isEqualTo("RAW");
        assertThat(metadata.get("requestedSelection").get("ranges").size()).isEqualTo(2);
        assertThat(metadata.get("effectiveSelection")).isEqualTo(mapper.valueToTree(ranges));
        assertThat(metadata.get("summaryOutcome").asString()).isEqualTo("NOT_REQUESTED");
        assertThat(metadata.get("hasMore").asBoolean()).isTrue();
        assertThat(metadata.get("nextCursor").asString()).isEqualTo("opaque-next-page");
        assertThat(metadata.get("stopReason").asString()).isEqualTo("CONTENT_BUDGET");
        assertThat(metadata.get("navigationHint").asBoolean()).isTrue();
    }

    @Test
    void retains_mid_line_continuation_coordinates_and_marks_summary_elision_unread() throws Exception {
        String path = "src/Continuation.java";
        Selection continuationSelection = new Selection(Optional.of(6), List.of(), Optional.empty(), 0, 0);
        SourceResult continuation = rawResult(path, 22, continuationSelection, List.of(new LineRange(6, 6)),
                List.of(sourceSegment(7, 13, 6, 6, "middle", true, true)), true,
                Optional.of("continue-in-line"), StopReason.CONTENT_BUDGET, false);

        String continuationText = SourceReadText.render(continuation, mapper);

        assertThat(continuationText).contains(
                mapper.writeValueAsString("sample@" + SHA + ":" + path + ":L6-L6"),
                "bytes=[7,13)", "lines=L6-L6", "startsMidLine=true", "endsMidLine=true", "6 | middle");
        assertThat(continuationText).doesNotContain("1 | middle");
        assertThat(metadata(continuationText).get("nextCursor").asString()).isEqualTo("continue-in-line");

        String declarationId = "b".repeat(64);
        Segment elision = new Segment(SegmentKind.ELISION, 10, 50, 3, 5, Optional.empty(), false, false,
                Optional.of("METHOD"), Optional.of(declarationId));
        SourceResult summary = new SourceResult(new SourceContext("sample", SHA), path, SHA, DIGEST, 100,
                View.AUTO, View.SUMMARY, new Selection(Optional.empty(), List.of(), Optional.empty(), 0, 0),
                List.of(), SummaryOutcome.ELIDED, Optional.of(GENERATION), Optional.of(IndexContract.syntaxProfile()),
                List.of(elision), false, Optional.empty(), StopReason.COMPLETE, true);

        String summaryText = SourceReadText.render(summary, mapper);

        assertThat(summaryText).contains(
                mapper.writeValueAsString("sample@" + SHA + ":" + path + ":L3-L5"),
                "bytes=[10,50)", "lines=L3-L5", "METHOD", declarationId, "unread source")
                .doesNotContain("3 | ", "4 | ", "5 | ");
        JsonNode summaryMetadata = metadata(summaryText);
        assertThat(summaryMetadata.get("requestedView").asString()).isEqualTo("AUTO");
        assertThat(summaryMetadata.get("effectiveView").asString()).isEqualTo("SUMMARY");
        assertThat(summaryMetadata.get("summaryOutcome").asString()).isEqualTo("ELIDED");
        assertThat(summaryMetadata.get("indexGeneration").asString()).isEqualTo(GENERATION);
        assertThat(summaryMetadata.get("profile")).isEqualTo(mapper.valueToTree(IndexContract.syntaxProfile()));
        assertThat(summaryMetadata.get("navigationHint").asBoolean()).isTrue();
    }

    @Test
    void distinguishes_empty_source_and_selection_and_does_not_add_an_eof_line() {
        Selection noSelection = new Selection(Optional.empty(), List.of(), Optional.empty(), 0, 0);
        SourceResult emptySource = rawResult("empty.txt", 0, noSelection, List.of(), List.of(), false,
                Optional.empty(), StopReason.COMPLETE, false);
        String emptySourceText = SourceReadText.render(emptySource, mapper);
        assertThat(emptySourceText).contains("no source segments").doesNotContain("1 | ");

        Selection outsideSelection = new Selection(Optional.of(20), List.of(), Optional.empty(), 0, 0);
        SourceResult emptySelection = rawResult("eof.txt", 10, outsideSelection, List.of(), List.of(), false,
                Optional.empty(), StopReason.COMPLETE, false);
        String emptySelectionText = SourceReadText.render(emptySelection, mapper);
        assertThat(emptySelectionText).contains("no source segments").doesNotContain("20 | ");

        String sourceAtEof = "one\n\nlast\n";
        byte[] sourceBytes = sourceAtEof.getBytes(StandardCharsets.UTF_8);
        Segment eofSegment = sourceSegment(0, sourceBytes.length, 1, 3, sourceAtEof, false, false);
        SourceResult eof = rawResult("eof.txt", sourceBytes.length, noSelection,
                List.of(new LineRange(1, 3)), List.of(eofSegment), false, Optional.empty(),
                StopReason.COMPLETE, false);

        String eofText = SourceReadText.render(eof, mapper);

        assertThat(eofText).contains("1 | one", "2 | \n", "3 | last").doesNotContain("4 | ");
    }

    private static Segment sourceSegment(long startByte, long endByte, int startLine, int endLine,
            String content, boolean startsMidLine, boolean endsMidLine) {
        return new Segment(SegmentKind.SOURCE, startByte, endByte, startLine, endLine, Optional.of(content),
                startsMidLine, endsMidLine, Optional.empty(), Optional.empty());
    }

    private static SourceResult rawResult(String path, long byteLength, Selection requestedSelection,
            List<LineRange> effectiveSelection, List<Segment> segments, boolean hasMore,
            Optional<String> nextCursor, StopReason stopReason, boolean navigationHint) {
        return new SourceResult(new SourceContext("sample", SHA), path, SHA, DIGEST, byteLength, View.RAW, View.RAW,
                requestedSelection, effectiveSelection, SummaryOutcome.NOT_REQUESTED, Optional.empty(), Optional.empty(),
                segments, hasMore, nextCursor, stopReason, navigationHint);
    }

    private JsonNode metadata(String presentation) throws Exception {
        String json = presentation.lines()
                .filter(line -> line.startsWith("metadata: "))
                .map(line -> line.substring("metadata: ".length()))
                .findFirst().orElseThrow();
        return mapper.readTree(json);
    }
}
