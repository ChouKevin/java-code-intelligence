package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.index.IndexFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Index-local, no-follow filesystem trust boundary. */
final class IndexFiles {
    static final long MAX_STATE = 4L * 1024 * 1024;
    static final long MAX_MANIFEST = 64 * 1024;
    private IndexFiles() { }

    static void safe(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("index path contains a symbolic link");
        }
    }

    static byte[] read(Path path, long maximum) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("index file unavailable");
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size > maximum || size > Integer.MAX_VALUE) throw new IOException("index file exceeds limit");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException("index file shortened");
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) throw new IOException("index file grew");
            return buffer.array();
        }
    }

    static String digest(Path path) throws IOException {
        safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("index file unavailable");
        return DurableSourceFiles.sha256(path);
    }

    static IndexFailure unavailable(Throwable cause) {
        return new IndexFailure(IndexFailure.Code.INDEX_UNAVAILABLE, cause);
    }
}
