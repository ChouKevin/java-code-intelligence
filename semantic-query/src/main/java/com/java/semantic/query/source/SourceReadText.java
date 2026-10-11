package com.java.semantic.query.source;

import com.java.semantic.model.index.IndexContract.Profile;
import com.java.semantic.model.source.SourceReadContract.LineRange;
import com.java.semantic.model.source.SourceReadContract.Segment;
import com.java.semantic.model.source.SourceReadContract.SegmentKind;
import com.java.semantic.model.source.SourceReadContract.Selection;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.StopReason;
import com.java.semantic.model.source.SourceReadContract.SummaryOutcome;
import com.java.semantic.model.source.SourceReadContract.View;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Model-facing display only; the structured result remains the raw source evidence. */
public final class SourceReadText {
    private static final String HEX = "0123456789abcdef";

    private SourceReadText() { }

    public static String render(SourceResult result, ObjectMapper mapper) {
        StringBuilder text = new StringBuilder();
        text.append("read_source repositoryId=");
        appendJson(text, result.context().repositoryId(), mapper);
        text.append(" revision=");
        appendJson(text, result.context().revision(), mapper);
        text.append(" path=");
        appendJson(text, result.path(), mapper);
        text.append("\nmetadata: ");
        appendJson(text, new Metadata(result.blobId(), result.contentDigest(), result.byteLength(),
                result.requestedView(), result.effectiveView(), result.requestedSelection(), result.effectiveSelection(),
                result.summaryOutcome(), result.indexGeneration(), result.profile(), result.hasMore(), result.nextCursor(),
                result.stopReason(), result.navigationHint()), mapper);
        text.append('\n');
        if (result.segments().isEmpty()) {
            text.append("[no source segments in this page]\n");
        }
        for (Segment segment : result.segments()) {
            text.append(segment.kind()).append(" locator=");
            appendJson(text, result.context().repositoryId() + "@" + result.context().revision() + ":"
                    + result.path() + ":L" + segment.startLine() + "-L" + segment.endLine(), mapper);
            text.append(" bytes=[").append(segment.startByte()).append(',').append(segment.endByte())
                    .append(") lines=L").append(segment.startLine()).append("-L").append(segment.endLine())
                    .append(" startsMidLine=").append(segment.startsMidLine())
                    .append(" endsMidLine=").append(segment.endsMidLine());
            if (segment.kind() == SegmentKind.ELISION) {
                text.append(" foldKind=");
                appendJson(text, segment.foldKind().orElseThrow(), mapper);
                text.append(" declarationId=");
                appendJson(text, segment.declarationId().orElseThrow(), mapper);
                text.append("\n[unread source omitted; read this range with view=\"raw\"]\n");
            } else {
                text.append('\n');
                appendSource(text, segment.content().orElseThrow(), segment.startLine());
            }
        }
        return text.toString();
    }

    private static void appendSource(StringBuilder text, String source, int firstLine) {
        int start = 0;
        int line = firstLine;
        while (start < source.length()) {
            int newline = source.indexOf('\n', start);
            int end = newline < 0 ? source.length() : newline;
            text.append(line).append(" | ");
            appendEscaped(text, source, start, end, true);
            text.append('\n');
            if (newline < 0) break;
            start = newline + 1;
            line++;
        }
    }

    private static void appendJson(StringBuilder text, Object value, ObjectMapper mapper) {
        String json = mapper.writeValueAsString(value);
        appendEscaped(text, json, 0, json.length(), false);
    }

    /** Append safe runs without splitting lines or allocating one string per character. */
    private static void appendEscaped(StringBuilder text, String value, int start, int end, boolean source) {
        int run = start;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            int codePoint = value.codePointAt(index);
            boolean escape = Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
                    || character == '\u2028' || character == '\u2029' || (source && character == '\\');
            if (!escape) continue;
            text.append(value, run, index);
            switch (character) {
                case '\\' -> text.append("\\\\");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '\b' -> text.append("\\b");
                case '\f' -> text.append("\\f");
                default -> appendUnicodeEscape(text, character);
            }
            if (Character.isSupplementaryCodePoint(codePoint)) {
                appendUnicodeEscape(text, value.charAt(++index));
            }
            run = index + 1;
        }
        text.append(value, run, end);
    }

    private static void appendUnicodeEscape(StringBuilder text, char character) {
        text.append("\\u").append(HEX.charAt((character >>> 12) & 15))
                .append(HEX.charAt((character >>> 8) & 15)).append(HEX.charAt((character >>> 4) & 15))
                .append(HEX.charAt(character & 15));
    }

    private record Metadata(String blobId, String contentDigest, long byteLength,
            View requestedView, View effectiveView, Selection requestedSelection, List<LineRange> effectiveSelection,
            SummaryOutcome summaryOutcome, Optional<String> indexGeneration, Optional<Profile> profile,
            boolean hasMore, Optional<String> nextCursor, StopReason stopReason, boolean navigationHint) { }
}
