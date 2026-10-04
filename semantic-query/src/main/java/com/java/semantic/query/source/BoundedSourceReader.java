package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Cursor positions are checked against actual LF boundaries and the sealed content digest. */
public final class BoundedSourceReader {
    private final SourceAccessProperties properties;
    private final SourcePathResolver resolver;

    public BoundedSourceReader(SourceAccessProperties properties, SourcePathResolver resolver) {
        this.properties = Objects.requireNonNull(properties);
        this.resolver = Objects.requireNonNull(resolver);
    }

    public SourceResult read(AdmittedSourceRevision admitted, ReadSourceRequest request) {
        if (!request.context().equals(admitted.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        long deadline = LocalSourceRevisionCatalog.deadline(properties.readTimeout());
        SourceInventoryEntry entry = resolver.entry(admitted, request.path(), deadline);
        Path file = resolver.readable(admitted, entry);
        String binding = QueryCursorCodec.binding("source_read", List.of(admitted.context().repositoryId(),
                admitted.context().revision(), admitted.manifestDigest(), request.path(), Integer.toString(request.startLine()),
                Integer.toString(request.maxLines()), entry.contentDigest().orElseThrow()));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer hashBuffer = ByteBuffer.allocate(8192);
            while (channel.read(hashBuffer) >= 0) {
                LocalSourceRevisionCatalog.check(deadline);
                hashBuffer.flip();
                digest.update(hashBuffer);
                hashBuffer.clear();
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(entry.contentDigest().orElseThrow())) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            }
            long offset = 0;
            int line = 1;
            if (request.cursor().isPresent()) {
                List<String> positions;
                try { positions = QueryCursorCodec.decode(request.cursor().orElseThrow(), binding, 2); }
                catch (IllegalArgumentException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
                try { offset = Long.parseLong(positions.get(0)); line = Integer.parseInt(positions.get(1)); }
                catch (NumberFormatException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
                if (offset <= 0 || offset >= size || line < request.startLine()) throw new SourceQueryException(Code.INVALID_ARGUMENT);
            }
            channel.position(0);
            InputStream input = new BufferedInputStream(Channels.newInputStream(channel), 8192);
            long position = 0;
            int counted = 1;
            int prior = -1;
            while (position < offset || request.cursor().isEmpty() && counted < request.startLine()) {
                LocalSourceRevisionCatalog.check(deadline);
                int value = input.read();
                if (value < 0) break;
                position++;
                if (value == '\n') counted++;
                prior = value;
            }
            if (request.cursor().isEmpty()) { offset = position; line = counted; }
            input.mark(1);
            int nextByte = input.read();
            input.reset();
            if (position != offset || counted != line || nextByte >= 0 && (nextByte & 0xc0) == 0x80) {
                throw new SourceQueryException(Code.INVALID_ARGUMENT);
            }
            boolean startsMid = offset > 0 && prior != '\n';
            int pageStartLine = line;
            byte[] content = new byte[properties.readContentBytes()];
            int wireBudget = 524_288 - 8192 - wireCost(request.path())
                    - wireCost(admitted.context().repositoryId()) - wireCost(admitted.context().revision());
            if (wireBudget < 26) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            int wireUsed = 0;
            int length = 0;
            int lines = 0;
            int endLine = line;
            while (position < size && lines < request.maxLines()) {
                LocalSourceRevisionCatalog.check(deadline);
                input.mark(4);
                int value = input.read();
                int width = value < 0x80 ? 1 : value >= 0xc2 && value <= 0xdf ? 2
                        : value >= 0xe0 && value <= 0xef ? 3 : value >= 0xf0 && value <= 0xf4 ? 4 : 0;
                if (width == 0) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                int cost = width == 1 ? (value < 0x20 ? 14 : value == '\"' || value == '\\' ? 6 : 2)
                        : width == 4 ? 26 : 13;
                if (length + width > content.length || wireUsed + cost > wireBudget) { input.reset(); break; }
                wireUsed += cost;
                content[length++] = (byte) value;
                for (int index = 1; index < width; index++) {
                    int continuation = input.read();
                    if (continuation < 0x80 || continuation > 0xbf) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                    content[length++] = (byte) continuation;
                }
                position += width;
                endLine = line;
                if (value == '\n') { lines++; if (position < size) line++; }
                if (value == '\n' && lines == request.maxLines()) break;
            }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content, 0, length)).toString();
            boolean more = position < size;
            Optional<String> cursor = more ? Optional.of(QueryCursorCodec.encode(binding,
                    List.of(Long.toString(position), Integer.toString(line)))) : Optional.empty();
            boolean endsMid = length > 0 && content[length - 1] != '\n' && more;
            return new SourceResult(admitted.context(), request.path(), pageStartLine,
                    length == 0 ? Optional.empty() : Optional.of(endLine), text, more, cursor,
                    length > 0 && startsMid, endsMid,
                    admitted.manifest().projectGuide().state() == com.java.semantic.model.source.SourceReadContract.GuideState.AVAILABLE
                            && admitted.manifest().projectGuide().path().filter(request.path()::equals).isPresent());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }
    private static int wireCost(String text) {
        int cost = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            cost += character < 0x20 || character > 0x7e ? 13
                    : character == '\"' || character == '\\' ? 6 : 2;
        }
        return cost;
    }

}
