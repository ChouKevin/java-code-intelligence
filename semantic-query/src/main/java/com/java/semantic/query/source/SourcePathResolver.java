package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
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

    /** Resolve only this bounded search window while verifying the complete inventory digest/order. */
    public Map<String, SourceInventoryEntry> entries(AdmittedSourceRevision admitted, Set<String> paths, long deadline) {
        Map<String, SourceInventoryEntry> found = new HashMap<>();
        scan(admitted, deadline, entry -> {
            if (paths.contains(entry.path())) found.put(entry.path(), entry);
        });
        return found;
    }

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
