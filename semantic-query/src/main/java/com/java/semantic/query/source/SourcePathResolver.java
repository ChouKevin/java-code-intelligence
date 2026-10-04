package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** Streams canonical inventory, and never follows filesystem links into source data. */
public final class SourcePathResolver {
    private final ObjectMapper mapper;
    public SourcePathResolver(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public String inventoryDigest(Path inventory, long deadline) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(inventory, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    LocalSourceRevisionCatalog.check(deadline);
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    public void scan(AdmittedSourceRevision admitted, long deadline, Consumer<SourceInventoryEntry> consumer) {
        LocalSourceRevisionCatalog.noLinks(admitted.inventory());
        try (InputStream input = new BufferedInputStream(Files.newInputStream(admitted.inventory(), LinkOption.NOFOLLOW_LINKS))) {
            verify(input, admitted, deadline, consumer);
        } catch (IOException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    public InventoryLookup openVerified(AdmittedSourceRevision admitted, long deadline) {
        LocalSourceRevisionCatalog.noLinks(admitted.inventory());
        try {
            FileChannel channel = FileChannel.open(admitted.inventory(),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            try {
                verify(new BufferedInputStream(Channels.newInputStream(channel)), admitted, deadline, entry -> { });
                return new InventoryLookup(channel, channel.size(), deadline);
            } catch (RuntimeException | IOException exception) {
                channel.close();
                throw exception;
            }
        } catch (IOException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    private void verify(InputStream input, AdmittedSourceRevision admitted, long deadline,
            Consumer<SourceInventoryEntry> consumer) {
        try {
            ByteArrayOutputStream frame = new ByteArrayOutputStream(1024);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String previous = "";
            boolean first = true;
            int next;
            while ((next = input.read()) != -1) {
                LocalSourceRevisionCatalog.check(deadline);
                digest.update((byte) next);
                if (next != '\n') {
                    if (frame.size() >= 65_536) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                    frame.write(next);
                    continue;
                }
                SourceInventoryEntry entry = mapper.readValue(frame.toByteArray(), SourceInventoryEntry.class);
                String key = key(entry);
                if (SourcePathPolicy.isExcluded(entry.path()) || (!first && compare(previous, key) >= 0)) {
                    throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
                }
                first = false;
                previous = key;
                consumer.accept(entry);
                frame.reset();
            }
            if (frame.size() != 0 || !HexFormat.of().formatHex(digest.digest())
                    .equals(admitted.manifest().inventoryDigest())) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        } catch (IOException | IllegalArgumentException | NoSuchAlgorithmException exception) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    public SourceInventoryEntry entry(AdmittedSourceRevision admitted, String path, long deadline) {
        String safe = safeFile(path);
        SourceInventoryEntry[] result = new SourceInventoryEntry[1];
        scan(admitted, deadline, item -> {
            if (item.path().equals(safe)) result[0] = item;
        });
        if (Objects.isNull(result[0])) throw new SourceQueryException(Code.SOURCE_NOT_FOUND);
        return result[0];
    }

    public final class InventoryLookup implements AutoCloseable {
        private final FileChannel channel;
        private final long size;
        private final long deadline;
        private final ByteBuffer block = ByteBuffer.allocate(1024);
        private final ByteBuffer single = ByteBuffer.allocate(1);
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream(1024);
        private String previousPath;
        private Optional<SourceInventoryEntry> previousResult = Optional.empty();

        private InventoryLookup(FileChannel channel, long size, long deadline) {
            this.channel = channel;
            this.size = size;
            this.deadline = deadline;
        }

        public Optional<SourceInventoryEntry> find(String path) {
            LocalSourceRevisionCatalog.check(deadline);
            if (path.equals(previousPath)) return previousResult;
            Optional<SourceInventoryEntry> result = seek(path);
            previousPath = path;
            previousResult = result;
            return result;
        }

        private Optional<SourceInventoryEntry> seek(String path) {
            try {
                long low = 0;
                long high = size;
                while (low < high) {
                    LocalSourceRevisionCatalog.check(deadline);
                    long midpoint = low + (high - low) / 2;
                    long start = lineStart(midpoint);
                    if (start >= high) {
                        high = midpoint;
                        continue;
                    }
                    InventoryLine line = readLine(start);
                    int order = compare(key(line.entry()), path);
                    if (order == 0) return Optional.of(line.entry());
                    if (order < 0) low = line.end();
                    else high = start;
                }
                return Optional.empty();
            } catch (IOException exception) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception);
            }
        }

        public Map<String, SourceInventoryEntry> entries(Set<String> paths) {
            Map<String, SourceInventoryEntry> found = new HashMap<>();
            for (String path : paths) find(path).ifPresent(entry -> found.put(path, entry));
            return found;
        }

        private long lineStart(long offset) throws IOException {
            if (offset == 0 || byteAt(offset - 1) == '\n') return offset;
            long position = offset;
            while (position < size) {
                LocalSourceRevisionCatalog.check(deadline);
                block.clear();
                int count = channel.read(block, position);
                if (count <= 0) throw new IOException("incomplete inventory line");
                block.flip();
                for (int index = 0; index < count; index++) {
                    if (block.get() == '\n') return position + index + 1;
                }
                position += count;
            }
            throw new IOException("unterminated inventory line");
        }

        private byte byteAt(long offset) throws IOException {
            single.clear();
            if (channel.read(single, offset) != 1) throw new IOException("incomplete inventory line");
            return single.get(0);
        }

        private InventoryLine readLine(long start) throws IOException {
            frame.reset();
            long position = start;
            while (position < size) {
                LocalSourceRevisionCatalog.check(deadline);
                block.clear();
                int count = channel.read(block, position);
                if (count <= 0) throw new IOException("incomplete inventory line");
                block.flip();
                for (int index = 0; index < count; index++) {
                    byte next = block.get();
                    if (next == '\n') {
                        try {
                            return new InventoryLine(mapper.readValue(frame.toByteArray(), SourceInventoryEntry.class),
                                    position + index + 1);
                        } catch (RuntimeException exception) {
                            throw new IOException("invalid inventory entry", exception);
                        }
                    }
                    if (frame.size() >= 65_536) throw new IOException("inventory frame too large");
                    frame.write(next);
                }
                position += count;
            }
            throw new IOException("unterminated inventory line");
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record InventoryLine(SourceInventoryEntry entry, long end) { }

    public Path readable(AdmittedSourceRevision admitted, SourceInventoryEntry entry) {
        if (entry.kind() != EntryKind.FILE || entry.status().orElseThrow() != EntryStatus.TEXT) {
            throw new SourceQueryException(Code.SOURCE_UNSUPPORTED);
        }
        Path path = physical(admitted, entry.path());
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != entry.byteLength()) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            }
        } catch (IOException exception) { throw new SourceQueryException(Code.SOURCE_UNAVAILABLE, exception); }
        return path;
    }

    public Path physical(AdmittedSourceRevision admitted, String path) {
        String safe = safeFile(path);
        Path root = admitted.tree().toAbsolutePath().normalize();
        LocalSourceRevisionCatalog.noLinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        Path result = root;
        String[] segments = safe.split("/");
        for (int index = 0; index < segments.length; index++) {
            result = result.resolve(segments[index]);
            if (!result.normalize().startsWith(root) || Files.isSymbolicLink(result)
                    || (index < segments.length - 1 && !Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS))) {
                throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
            }
        }
        return result;
    }

    public static String safeFile(String path) {
        try {
            SourcePathPolicy.requireFile(path);
            if (SourcePathPolicy.isExcluded(path)) throw new IllegalArgumentException("excluded");
            return path;
        } catch (IllegalArgumentException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
    }

    public static String safeDirectory(String path) {
        try {
            SourcePathPolicy.requireDirectory(path);
            if (SourcePathPolicy.isExcluded(path)) throw new IllegalArgumentException("excluded");
            return path;
        } catch (IllegalArgumentException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
    }

    public static String key(SourceInventoryEntry entry) {
        return entry.path() + (entry.kind() == EntryKind.DIRECTORY ? "/" : "");
    }

    public static int compare(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int count = Math.min(a.length, b.length);
        for (int index = 0; index < count; index++) {
            int result = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (result != 0) return result;
        }
        return Integer.compare(a.length, b.length);
    }

    public static boolean matches(String path, String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            if (character == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    regex.append(".*"); index++;
                } else regex.append("[^/]*");
            } else if (character == '?') regex.append("[^/]");
            else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return path.matches(regex.append('$').toString());
    }

    public static String safeGlob(String glob) {
        safeFile(glob);
        int wildcards = 0;
        for (int index = 0; index < glob.length(); index++) {
            if (glob.charAt(index) == '*' || glob.charAt(index) == '?') wildcards++;
        }
        if (glob.length() > 256 || wildcards > 8 || glob.contains("***") || glob.charAt(0) == '!'
                || glob.indexOf('[') >= 0 || glob.indexOf(']') >= 0
                || glob.indexOf('{') >= 0 || glob.indexOf('}') >= 0 || glob.indexOf('\\') >= 0) {
            throw new SourceQueryException(Code.INVALID_ARGUMENT);
        }
        return glob;
    }
}
