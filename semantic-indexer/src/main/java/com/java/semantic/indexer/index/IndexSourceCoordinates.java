package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract.SourceSpan;
import java.io.IOException;
import java.util.Arrays;

/** Raw byte line starts are computed once per file, not once per declaration. */
final class IndexSourceCoordinates {
    private final byte[] bytes;
    private final int[] lines;

    IndexSourceCoordinates(byte[] bytes) {
        this.bytes = bytes;
        int count = 1;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') count++;
        }
        lines = new int[count];
        int next = 1;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') lines[next++] = index + 1;
        }
    }

    void validate(SourceSpan range) throws IOException {
        if (range.endByte() > bytes.length || !boundary(range.startByte()) || !boundary(range.endByte()))
            throw new IOException("range is outside UTF-8 boundaries");
        if (range.startLine() != line((int) range.startByte())
                || range.endLine() != line((int) Math.max(range.startByte(), range.endByte() - 1)))
            throw new IOException("range line mismatch");
    }
    private boolean boundary(long position) {
        return position == bytes.length || (bytes[(int) position] & 0xc0) != 0x80;
    }
    private int line(int position) {
        int found = Arrays.binarySearch(lines, position);
        return found >= 0 ? found + 1 : -found - 1;
    }
}
