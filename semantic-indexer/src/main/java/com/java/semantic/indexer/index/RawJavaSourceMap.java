package com.java.semantic.indexer.index;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.java.semantic.model.index.IndexContract.SourceSpan;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Translates JLS 3.3 escapes once and maps parser UTF-16 boundaries to exact raw UTF-8 evidence. */
final class RawJavaSourceMap {
    private static final int[] IDENTITY_OFFSETS = new int[0];
    private final String source;
    private final int[] parserLineStarts;
    private final int[] byteOffsetByUtf16Offset;
    private final int[] lfByteLineStarts;
    private final int[] rawUtf16Offsets;

    private RawJavaSourceMap(String source, byte[] rawBytes) {
        DecodedUnicode decoded = decodeUnicode(source);
        this.source = decoded.source();
        this.rawUtf16Offsets = decoded.rawOffsets();
        this.parserLineStarts = buildParserLineStarts(this.source);
        this.byteOffsetByUtf16Offset = buildByteOffsets(source);
        this.lfByteLineStarts = buildLfByteLineStarts(rawBytes);
    }

    static RawJavaSourceMap decode(byte[] rawBytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes));
        return new RawJavaSourceMap(decoded.toString(), rawBytes);
    }

    String source() {
        return source;
    }

    Optional<SourceSpan> span(Range parserRange) {
        // The exclusive boundary includes the complete raw spelling of a final Unicode escape.
        Position parsedStart = parserRange.begin;
        Position parsedEnd = new Position(parserRange.end.line, parserRange.end.column + 1);
        Optional<Integer> startCharacter = characterOffset(parsedStart);
        Optional<Integer> endCharacter = characterOffset(parsedEnd);
        if (startCharacter.isEmpty() || endCharacter.isEmpty()) {
            return Optional.empty();
        }
        int startUtf16 = rawOffset(startCharacter.orElseThrow());
        int endUtf16 = rawOffset(endCharacter.orElseThrow());
        Optional<Integer> startByte = byteOffset(startUtf16);
        Optional<Integer> endByte = byteOffset(endUtf16);
        if (startByte.isEmpty() || endByte.isEmpty()) {
            return Optional.empty();
        }
        int start = startByte.orElseThrow();
        int end = endByte.orElseThrow();
        if (end <= start) {
            return Optional.empty();
        }
        return Optional.of(new SourceSpan(start, end, lfLineAt(start), lfLineAt(end - 1)));
    }

    SourceSpan interior(SourceSpan opening, SourceSpan closing) {
        int start = Math.toIntExact(opening.endByte());
        int end = Math.toIntExact(closing.startByte());
        return new SourceSpan(start, end, lfLineAt(start), lfLineAt(Math.max(start, end - 1)));
    }

    private int rawOffset(int parsedOffset) {
        return rawUtf16Offsets.length == 0 ? parsedOffset : rawUtf16Offsets[parsedOffset];
    }

    private static DecodedUnicode decodeUnicode(String raw) {
        int firstEscape = firstUnicodeEscape(raw);
        if (firstEscape < 0) return new DecodedUnicode(raw, IDENTITY_OFFSETS);
        StringBuilder translated = new StringBuilder(raw.length()).append(raw, 0, firstEscape);
        int[] boundaries = new int[raw.length() + 1];
        for (int index = 0; index <= firstEscape; index++) boundaries[index] = index;
        int backslashes = 0;
        boolean previousWasEscape = false;
        int outputOffset = firstEscape;
        for (int inputOffset = firstEscape; inputOffset < raw.length();) {
            char character = raw.charAt(inputOffset++);
            boolean escaped = false;
            if (character == '\\' && (previousWasEscape || backslashes % 2 == 0)
                    && inputOffset < raw.length() && raw.charAt(inputOffset) == 'u') {
                do { inputOffset++; } while (inputOffset < raw.length() && raw.charAt(inputOffset) == 'u');
                if (raw.length() - inputOffset < 4) throw new InvalidUnicodeEscapeException();
                int value = 0;
                for (int digitIndex = 0; digitIndex < 4; digitIndex++) {
                    char digitCharacter = raw.charAt(inputOffset++);
                    int digit = Character.digit(digitCharacter, 16);
                    if (digitCharacter > 0x7f || digit < 0) throw new InvalidUnicodeEscapeException();
                    value = value * 16 + digit;
                }
                character = (char) value;
                escaped = true;
            }
            translated.append(character);
            boundaries[++outputOffset] = inputOffset;
            backslashes = character == '\\' ? backslashes + 1 : 0;
            previousWasEscape = escaped;
        }
        return new DecodedUnicode(translated.toString(), boundaries);
    }

    private static int firstUnicodeEscape(String raw) {
        int backslashes = 0;
        for (int index = 0; index < raw.length(); index++) {
            if (raw.charAt(index) == '\\') {
                if (backslashes % 2 == 0 && index + 1 < raw.length() && raw.charAt(index + 1) == 'u') return index;
                backslashes++;
            } else {
                backslashes = 0;
            }
        }
        return -1;
    }

    private record DecodedUnicode(String source, int[] rawOffsets) {}

    static final class InvalidUnicodeEscapeException extends RuntimeException {}

    private static int[] buildParserLineStarts(String source) {
        int lineCount = 1;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\r') {
                lineCount++;
                if (index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    index++;
                }
            } else if (character == '\n') {
                lineCount++;
            }
        }
        int[] lineStarts = new int[lineCount];
        int nextLine = 1;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\r') {
                if (index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    index++;
                }
                lineStarts[nextLine++] = index + 1;
            } else if (character == '\n') {
                lineStarts[nextLine++] = index + 1;
            }
        }
        return lineStarts;
    }

    private static int[] buildByteOffsets(String source) {
        int[] byteOffsets = new int[source.length() + 1];
        int utf16Offset = 0;
        int byteOffset = 0;
        while (utf16Offset < source.length()) {
            int codePoint = source.codePointAt(utf16Offset);
            int utf16Width = Character.charCount(codePoint);
            byteOffsets[utf16Offset] = byteOffset;
            if (utf16Width == 2) {
                byteOffsets[utf16Offset + 1] = -1;
            }
            byteOffset += utf8Width(codePoint);
            utf16Offset += utf16Width;
            byteOffsets[utf16Offset] = byteOffset;
        }
        return byteOffsets;
    }

    private static int[] buildLfByteLineStarts(byte[] rawBytes) {
        int lineCount = 1;
        for (byte rawByte : rawBytes) {
            if (rawByte == '\n') {
                lineCount++;
            }
        }
        int[] lineStarts = new int[lineCount];
        int nextLine = 1;
        for (int index = 0; index < rawBytes.length; index++) {
            if (rawBytes[index] == '\n') {
                lineStarts[nextLine++] = index + 1;
            }
        }
        return lineStarts;
    }

    private Optional<Integer> characterOffset(Position position) {
        if (!position.valid() || position.line > parserLineStarts.length) {
            return Optional.empty();
        }
        int lineIndex = position.line - 1;
        int lineStart = parserLineStarts[lineIndex];
        int lineEnd = lineIndex + 1 == parserLineStarts.length
                ? source.length()
                : parserLineStarts[lineIndex + 1] - lineTerminatorWidth(lineIndex + 1);
        long requested = (long) lineStart + position.column - 1;
        if (requested < lineStart || requested > lineEnd) {
            return Optional.empty();
        }
        return Optional.of((int) requested);
    }

    private int lineTerminatorWidth(int nextLineIndex) {
        int nextLineStart = parserLineStarts[nextLineIndex];
        if (nextLineStart >= 2 && source.charAt(nextLineStart - 2) == '\r'
                && source.charAt(nextLineStart - 1) == '\n') {
            return 2;
        }
        return 1;
    }

    private Optional<Integer> byteOffset(int utf16Offset) {
        if (utf16Offset < 0 || utf16Offset >= byteOffsetByUtf16Offset.length) {
            return Optional.empty();
        }
        int offset = byteOffsetByUtf16Offset[utf16Offset];
        return offset < 0 ? Optional.empty() : Optional.of(offset);
    }

    private int lfLineAt(int byteOffset) {
        int index = Arrays.binarySearch(lfByteLineStarts, byteOffset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    private static int utf8Width(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        return codePoint <= 0xffff ? 3 : 4;
    }
}
