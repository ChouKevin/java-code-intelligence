package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryRevision;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic local-fixture revision over meaningful selected inputs, independent of host path. */
@Component
public final class FixtureRevisionResolver {
    private final FixtureInputSelector selector;

    public FixtureRevisionResolver() {
        this(new FixtureInputSelector());
    }

    FixtureRevisionResolver(FixtureInputSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector is required");
    }

    public RepositoryRevision resolve(Path root) {
        Path normalizedRoot = Objects.requireNonNull(root, "root is required").toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(normalizedRoot) || !Files.isDirectory(normalizedRoot)
                    || !isReadable(normalizedRoot)) {
                throw new IllegalArgumentException("fixture root is unavailable or unsafe");
            }
            Path realRoot = normalizedRoot.toRealPath();
            List<FixtureFile> files = new ArrayList<>();
            Set<String> normalizedPaths = new HashSet<>();
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (Files.isSymbolicLink(directory)) {
                        throw new IllegalArgumentException("fixture symlinks are not allowed");
                    }
                    Path relative = normalizedRoot.relativize(directory);
                    return selector.excludes(relative) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile() || !isReadable(file)
                            || !file.toRealPath().startsWith(realRoot)) {
                        throw new IllegalArgumentException("fixture file is unavailable or unsafe");
                    }
                    Path relative = normalizedRoot.relativize(file).normalize();
                    if (relative.startsWith("..") || selector.excludes(relative) || !selector.includes(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String path = Normalizer.normalize(relative.toString().replace(file.getFileSystem().getSeparator(), "/"), Normalizer.Form.NFC);
                    if (!normalizedPaths.add(path)) {
                        throw new IllegalArgumentException("fixture contains Unicode-normalization collision");
                    }
                    files.add(new FixtureFile(path, Files.readAllBytes(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.sort(Comparator.comparing(FixtureFile::path, FixtureRevisionResolver::compareUtf8));
            for (FixtureFile file : files) {
                digest.update(file.path().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(file.bytes().length).array());
                digest.update(file.bytes());
            }
            return new RepositoryRevision(java.util.HexFormat.of().formatHex(digest.digest(), 0, 20));
        } catch (IOException exception) {
            throw new IllegalArgumentException("fixture input cannot be read", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static boolean isReadable(Path path) {
        if (!Files.isReadable(path)) {
            return false;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            return permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
        } catch (UnsupportedOperationException exception) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int comparison = Byte.compareUnsigned(leftBytes[index], rightBytes[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private record FixtureFile(String path, byte[] bytes) { }
}
