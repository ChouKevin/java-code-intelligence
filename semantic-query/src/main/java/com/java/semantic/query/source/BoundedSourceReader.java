package com.java.semantic.query.source;

import tools.jackson.databind.ObjectMapper;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Reads sealed bytes, retaining only selected boundaries and bounded page content. */
public final class BoundedSourceReader {
    private final SourceAccessProperties properties;
    private final SourcePathResolver resolver;
    private final ObjectMapper mapper;

    public BoundedSourceReader(SourceAccessProperties properties, SourcePathResolver resolver, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.resolver = Objects.requireNonNull(resolver);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public SourceResult read(AdmittedSourceRevision source, ReadSourceRequest request,
            Function<Optional<String>, IndexContract.FoldResult> folds) {
        if (!request.context().equals(source.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        long deadline = LocalSourceRevisionCatalog.deadline(properties.readTimeout());
        SourceInventoryEntry entry = resolver.entry(source, request.path(), deadline);
        Path path = resolver.readable(source, entry);
        String binding = QueryCursorCodec.binding("source_read_segments", List.of(source.context().repositoryId(),
                source.context().revision(), source.manifestDigest(), entry.contentDigest().orElseThrow(), request.path(),
                request.view().name(), request.startLine().toString(), request.ranges().toString(), request.tailLines().toString(),
                Integer.toString(request.maxLines()), Integer.toString(request.contextBefore()),
                Integer.toString(request.contextAfter()), request.indexGeneration().orElse("")));
        List<String> cursor = request.cursor().map(value -> decode(value, binding)).orElse(List.of());
        if (!cursor.isEmpty() && request.indexGeneration().isPresent()
                && !request.indexGeneration().orElseThrow().equals(cursor.get(1))) {
            throw new SourceQueryException(Code.INVALID_ARGUMENT);
        }
        try {
            Inspection inspection = inspect(path, entry, request.tailLines().orElse(0), deadline);
            int totalLines = inspection.lines();
            List<LineRange> selection = normalize(request, totalLines);
            View effective = View.RAW;
            SummaryOutcome outcome = SummaryOutcome.NOT_REQUESTED;
            Optional<IndexContract.FoldResult> metadata = Optional.empty();
            boolean summary = request.view() == View.SUMMARY || request.view() == View.AUTO && !request.hasSelector();
            if (!cursor.isEmpty()) {
                effective = View.valueOf(cursor.get(0));
                outcome = SummaryOutcome.valueOf(cursor.get(2));
                if (effective == View.AUTO || request.view() == View.RAW && effective != View.RAW
                        || request.view() == View.SUMMARY && effective != View.SUMMARY
                        || request.hasSelector() && effective != View.RAW) throw new SourceQueryException(Code.INVALID_ARGUMENT);
                summary = effective == View.SUMMARY;
            }
            if (summary) {
                if (!request.path().endsWith(".java")) outcome = SummaryOutcome.UNSUPPORTED_LANGUAGE;
                else if (entry.byteLength() > IndexContract.MAX_FILE_BYTES) outcome = SummaryOutcome.FILE_TOO_LARGE;
                else if (cursor.isEmpty() && request.view() == View.AUTO && totalLines < 100) outcome = SummaryOutcome.BELOW_SUMMARY_THRESHOLD;
                else {
                    Optional<String> generation = cursor.isEmpty() ? request.indexGeneration() : Optional.of(cursor.get(1));
                    try {
                        IndexContract.FoldResult result = folds.apply(generation);
                        if (generation.isPresent() && !generation.orElseThrow().equals(result.indexGeneration())) {
                            throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE);
                        }
                        if (!result.file().path().equals(request.path()) || result.file().byteLength() != entry.byteLength()
                                || !result.file().contentDigest().equals(entry.contentDigest()) || !result.file().blobId().equals(entry.blobId())) {
                            throw new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE);
                        }
                        metadata = Optional.of(result);
                        outcome = !result.foldCapable() ? SummaryOutcome.FOLD_METADATA_UNAVAILABLE
                                : switch (result.file().status()) {
                                    case PARSED -> SummaryOutcome.NO_ELISION;
                                    case PARSE_FAILED -> SummaryOutcome.FILE_PARSE_FAILED;
                                    case UNSUPPORTED -> SummaryOutcome.FILE_UNSUPPORTED;
                                    case TOO_LARGE -> SummaryOutcome.FILE_TOO_LARGE;
                                };
                    } catch (IndexFailure failure) {
                        if (generation.isPresent()) throw failure;
                        outcome = switch (failure.code()) {
                            case INDEX_NOT_PREPARED -> SummaryOutcome.INDEX_NOT_READY;
                            case INDEX_UNAVAILABLE, INDEX_INCOMPATIBLE -> SummaryOutcome.INDEX_UNAVAILABLE;
                            default -> throw failure;
                        };
                    }
                }
                if (outcome != SummaryOutcome.NO_ELISION) {
                    if (request.view() == View.SUMMARY || !cursor.isEmpty()) throw new SourceQueryException(outcome);
                    metadata = Optional.empty();
                } else effective = View.SUMMARY;
            }
            List<IndexContract.Fold> candidates = metadata.map(IndexContract.FoldResult::folds).orElse(List.of());
            Map<Integer, Long> boundaries;
            if (request.tailLines().isPresent()) {
                boundaries = new HashMap<>();
                if (!selection.isEmpty()) {
                    int first = selection.getFirst().startLine();
                    boundaries.put(first, inspection.tailOffsets()[(first - 1) % inspection.tailOffsets().length]);
                    boundaries.put(totalLines + 1, entry.byteLength());
                }
            } else boundaries = boundaries(path, selection, candidates, totalLines, entry.byteLength(), deadline);
            List<Window> windows = windows(selection, candidates, boundaries, entry.byteLength());
            boolean elided = windows.stream().anyMatch(window -> window.fold().isPresent());
            if (effective == View.SUMMARY) {
                outcome = elided ? SummaryOutcome.ELIDED : SummaryOutcome.NO_ELISION;
                if (!elided && request.view() == View.AUTO && cursor.isEmpty()) {
                    effective = View.RAW;
                    metadata = Optional.empty();
                }
            }
            if (effective == View.RAW) windows = windows(selection, List.of(), boundaries, entry.byteLength());
            Optional<String> generation = metadata.map(IndexContract.FoldResult::indexGeneration);
            Optional<IndexContract.Profile> profile = metadata.map(IndexContract.FoldResult::profile);
            String effectiveBinding = QueryCursorCodec.binding("source_read_view", List.of(binding, effective.name(),
                    generation.orElse(""), profile.map(Object::toString).orElse(""), selection.toString(), windows.toString()));
            if (!cursor.isEmpty() && !cursor.get(6).equals(effectiveBinding)) throw new SourceQueryException(Code.INVALID_ARGUMENT);
            if (!cursor.isEmpty() && (!cursor.get(1).equals(generation.orElse(""))
                    || !cursor.get(5).equals(profile.map(Object::toString).orElse("")))) throw new SourceQueryException(Code.INVALID_ARGUMENT);
            long offset = cursor.isEmpty() ? (windows.isEmpty() ? entry.byteLength() : windows.getFirst().start()) : Long.parseLong(cursor.get(3));
            int line = cursor.isEmpty() ? (windows.isEmpty() ? 1 : windows.getFirst().firstLine()) : Integer.parseInt(cursor.get(4));
            if (!cursor.isEmpty()) validatePosition(path, windows, offset, line, deadline);
            int budget = Math.min(properties.readContentBytes(), 65_536);
            int segmentLimit = 500;
            SourceResult result;
            do {
                PageData page = page(path, windows, offset, line, budget, request.maxLines(), segmentLimit, deadline);
                Optional<String> next = page.more() ? Optional.of(SourceReadCursorCodec.encode(binding, List.of(effective.name(),
                        generation.orElse(""), outcome.name(), Long.toString(page.offset()), Integer.toString(page.line()),
                        profile.map(Object::toString).orElse(""), effectiveBinding))) : Optional.empty();
                result = new SourceResult(source.context(), request.path(), entry.blobId().orElseThrow(), entry.contentDigest().orElseThrow(),
                        entry.byteLength(), request.view(), effective,
                        new Selection(request.startLine(), request.ranges(), request.tailLines(), request.contextBefore(), request.contextAfter()),
                        selection, outcome, generation, profile, page.segments(), page.more(), next,
                        (budget < Math.min(properties.readContentBytes(), 65_536) || segmentLimit < 500) && page.more()
                                ? StopReason.WIRE_BUDGET : page.stop(),
                        source.manifest().projectGuide().path().filter(request.path()::equals).isPresent());
                if (SourceResponseBudget.fits(result, mapper)) break;
                if (page.segments().size() > 1) segmentLimit = Math.max(1, page.segments().size() / 2);
                else {
                    budget /= 2;
                    if (budget < 4) throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE);
                }
            } while (true);
            LocalSourceRevisionCatalog.check(deadline);
            return result;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        } catch (IllegalArgumentException exception) {
            throw new SourceQueryException(Code.INVALID_ARGUMENT, exception);
        }
    }

    private static List<String> decode(String cursor, String binding) {
        try { return SourceReadCursorCodec.decode(cursor, binding, 7); }
        catch (IllegalArgumentException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
    }

    private record Inspection(int lines, long[] tailOffsets) { }

    private static Inspection inspect(Path path, SourceInventoryEntry entry, int tailCount, long deadline)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long[] tailOffsets = new long[tailCount];
        long size = 0;
        int lines = 0;
        boolean startsLine = true;
        try (InputStream input = new BufferedInputStream(java.nio.file.Files.newInputStream(path))) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                LocalSourceRevisionCatalog.check(deadline);
                digest.update(buffer, 0, count);
                for (int index = 0; index < count; index++) {
                    if (startsLine) {
                        if (tailCount > 0) tailOffsets[lines % tailCount] = size + index;
                        lines = Math.incrementExact(lines);
                    }
                    startsLine = buffer[index] == '\n';
                }
                size += count;
            }
        }
        if (size != entry.byteLength() || !HexFormat.of().formatHex(digest.digest()).equals(entry.contentDigest().orElseThrow())) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
        return new Inspection(lines, tailOffsets);
    }

    private static List<LineRange> normalize(ReadSourceRequest request, int total) {
        if (total == 0) return List.of();
        List<LineRange> requested = request.ranges().isEmpty()
                ? List.of(new LineRange(request.tailLines().map(count -> Math.max(1, total - count + 1)).orElse(request.startLine().orElse(1)), total < request.startLine().orElse(1) ? request.startLine().orElse(1) : total))
                : request.ranges();
        List<LineRange> expanded = new ArrayList<>();
        for (LineRange range : requested) {
            if (range.startLine() > total) continue;
            expanded.add(new LineRange(Math.max(1, range.startLine() - request.contextBefore()),
                    (int) Math.min(total, (long) range.endLine() + request.contextAfter())));
        }
        expanded.sort(Comparator.comparingInt(LineRange::startLine));
        List<LineRange> merged = new ArrayList<>();
        for (LineRange range : expanded) {
            if (!merged.isEmpty() && (long) range.startLine() <= (long) merged.getLast().endLine() + 1) {
                LineRange previous = merged.removeLast();
                merged.add(new LineRange(previous.startLine(), Math.max(previous.endLine(), range.endLine())));
            } else merged.add(range);
        }
        return List.copyOf(merged);
    }

    private static Map<Integer, Long> boundaries(Path path, List<LineRange> selection, List<IndexContract.Fold> folds,
            int total, long size, long deadline) throws IOException {
        Map<Integer, Long> positions = new HashMap<>();
        for (LineRange range : selection) { positions.put(range.startLine(), -1L); positions.put(range.endLine() + 1, -1L); }
        for (IndexContract.Fold fold : folds) {
            positions.put(fold.interior().startLine() + 1, -1L);
            positions.put(fold.interior().endLine(), -1L);
            positions.put(fold.interior().endLine() + 1, -1L);
        }
        int line = 1;
        long offset = 0;
        if (positions.containsKey(line)) positions.put(line, offset);
        byte[] buffer = new byte[8192];
        try (InputStream input = new BufferedInputStream(java.nio.file.Files.newInputStream(path))) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                LocalSourceRevisionCatalog.check(deadline);
                for (int index = 0; index < count; index++) {
                    offset++;
                    if (buffer[index] == '\n') {
                        line++;
                        if (positions.containsKey(line)) positions.put(line, offset);
                    }
                }
            }
        }
        positions.put(total + 1, size);
        return positions;
    }

    private record Window(long start, long end, int firstLine, int lastLine, Optional<IndexContract.Fold> fold) { }

    private static List<Window> windows(List<LineRange> selection, List<IndexContract.Fold> candidates,
            Map<Integer, Long> boundaries, long size) {
        List<Window> bodies = new ArrayList<>();
        for (IndexContract.Fold fold : candidates) {
            IndexContract.SourceSpan span = fold.interior();
            if (span.endByte() > size) throw new SourceQueryException(SummaryOutcome.INDEX_UNAVAILABLE);
            int closingLine = boundaries.getOrDefault(span.endLine() + 1, -1L) == span.endByte()
                    ? span.endLine() + 1 : span.endLine();
            if ((long) closingLine - span.startLine() - 1 < 4) continue;
            long start = boundaries.getOrDefault(span.startLine() + 1, -1L);
            long end = boundaries.getOrDefault(closingLine, -1L);
            if (start < span.startByte() || end > span.endByte() || end <= start) throw new SourceQueryException(SummaryOutcome.INDEX_UNAVAILABLE);
            bodies.add(new Window(start, end, span.startLine() + 1, closingLine - 1, Optional.of(fold)));
        }
        bodies.sort(Comparator.comparingLong(Window::start).thenComparing(Comparator.comparingLong(Window::end).reversed()));
        List<Window> result = new ArrayList<>();
        for (LineRange range : selection) {
            long start = boundaries.get(range.startLine());
            long end = boundaries.get(range.endLine() + 1);
            int line = range.startLine();
            for (Window body : bodies) {
                if (body.start() < start || body.end() > end) continue;
                if (body.start() > start) result.add(new Window(start, body.start(), line, body.firstLine() - 1, Optional.empty()));
                result.add(body);
                start = body.end();
                line = body.lastLine() + 1;
            }
            if (start < end) result.add(new Window(start, end, line, range.endLine(), Optional.empty()));
        }
        return result;
    }

    private static void validatePosition(Path path, List<Window> windows, long offset, int line, long deadline) throws IOException {
        Window selected = windows.stream().filter(window -> offset >= window.start() && offset < window.end()).findFirst()
                .orElseThrow(() -> new SourceQueryException(Code.INVALID_ARGUMENT));
        if (selected.fold().isPresent() && offset != selected.start()) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(selected.start());
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long position = selected.start();
            int actualLine = selected.firstLine();
            while (position < offset) {
                LocalSourceRevisionCatalog.check(deadline);
                buffer.clear().limit((int) Math.min(buffer.capacity(), offset - position));
                int count = channel.read(buffer);
                if (count < 0) throw new SourceQueryException(Code.INVALID_ARGUMENT);
                buffer.flip();
                while (buffer.hasRemaining()) if (buffer.get() == '\n') actualLine++;
                position += count;
            }
            buffer.clear().limit(1);
            if (actualLine != line || channel.read(buffer) != 1 || (buffer.get(0) & 0xc0) == 0x80) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        }
    }

    private record PageData(List<Segment> segments, boolean more, long offset, int line, StopReason stop) { }

    private static PageData page(Path path, List<Window> windows, long offset, int line, int budget,
            int maxLines, int segmentLimit, long deadline) throws IOException {
        List<Segment> segments = new ArrayList<>();
        int used = 0;
        int lineCount = 0;
        int lastCounted = -1;
        StopReason stop = StopReason.COMPLETE;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            for (Window window : windows) {
                if (offset >= window.end()) continue;
                if (offset < window.start()) { offset = window.start(); line = window.firstLine(); }
                if (segments.size() == segmentLimit) { stop = StopReason.SEGMENT_BUDGET; break; }
                if (window.fold().isPresent()) {
                    IndexContract.Fold fold = window.fold().orElseThrow();
                    segments.add(new Segment(SegmentKind.ELISION, window.start(), window.end(), window.firstLine(), window.lastLine(),
                            Optional.empty(), false, false, Optional.of(fold.kind()), Optional.of(fold.declarationId())));
                    offset = window.end(); line = window.lastLine() + 1;
                    continue;
                }
                channel.position(offset);
                byte[] bytes = new byte[Math.min(budget - used, (int) Math.min(Integer.MAX_VALUE, window.end() - offset))];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) LocalSourceRevisionCatalog.check(deadline);
                int available = buffer.position();
                int length = 0;
                int firstLine = line;
                int endLine = line;
                long start = offset;
                boolean startsMid = false;
                if (offset > window.start()) {
                    ByteBuffer prior = ByteBuffer.allocate(1);
                    channel.read(prior, offset - 1);
                    startsMid = prior.get(0) != '\n';
                }
                while (length < available) {
                    LocalSourceRevisionCatalog.check(deadline);
                    int lead = bytes[length] & 255;
                    int width = lead < 128 ? 1 : lead >= 194 && lead <= 223 ? 2 : lead >= 224 && lead <= 239 ? 3 : lead >= 240 && lead <= 244 ? 4 : 0;
                    if (width == 0) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                    if (length + width > available) { stop = StopReason.CONTENT_BUDGET; break; }
                    if (line != lastCounted) {
                        if (lineCount == maxLines) { stop = StopReason.LINE_BUDGET; break; }
                        lineCount++; lastCounted = line;
                    }
                    endLine = line;
                    length += width;
                    if (lead == '\n') line++;
                }
                if (length > 0) {
                    String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
                    offset += length; used += length;
                    segments.add(new Segment(SegmentKind.SOURCE, start, offset, firstLine, endLine, Optional.of(content),
                            startsMid, offset < window.end() && bytes[length - 1] != '\n', Optional.empty(), Optional.empty()));
                }
                if (offset < window.end()) {
                    if (stop == StopReason.COMPLETE) stop = StopReason.CONTENT_BUDGET;
                    break;
                }
            }
        }
        long next = offset;
        Optional<Window> remaining = windows.stream().filter(window -> window.end() > next).findFirst();
        boolean more = remaining.isPresent();
        if (more && offset < remaining.orElseThrow().start()) {
            offset = remaining.orElseThrow().start(); line = remaining.orElseThrow().firstLine();
        }
        if (more && segments.isEmpty()) throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE);
        return new PageData(List.copyOf(segments), more, offset, line, more ? stop : StopReason.COMPLETE);
    }

}
