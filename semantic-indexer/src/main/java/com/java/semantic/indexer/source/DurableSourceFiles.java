package com.java.semantic.indexer.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** POSIX single-writer ownership and durable same-filesystem publication. No copy fallback. */
public final class DurableSourceFiles implements AutoCloseable {
    public enum Visibility {
        PRIVATE(PosixFilePermissions.fromString("rw-------"), PosixFilePermissions.fromString("rwx------")),
        PUBLISHED(PosixFilePermissions.fromString("rw-r--r--"), PosixFilePermissions.fromString("rwxr-xr-x"));

        private final Set<PosixFilePermission> file;
        private final Set<PosixFilePermission> directory;

        Visibility(Set<PosixFilePermission> file, Set<PosixFilePermission> directory) {
            this.file = file;
            this.directory = directory;
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    public DurableSourceFiles(Path adminRoot) throws IOException {
        ensureDirectories(adminRoot, adminRoot, Visibility.PRIVATE);
        Path lockPath = adminRoot.resolve("writer.lock");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            Files.setPosixFilePermissions(lockPath, Visibility.PRIVATE.file);
        } catch (IOException exception) {
            channel.close();
            throw exception;
        }
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException exception) {
            channel.close();
            throw new IOException("source store already has a writer", exception);
        }
        if (Objects.isNull(lock)) {
            channel.close();
            throw new IOException("source store already has a writer");
        }
        try {
            forceDirectory(adminRoot);
        } catch (IOException exception) {
            close();
            throw exception;
        }
    }

    public static void atomicBytes(Path destination, byte[] content, long maximum, Visibility visibility)
            throws IOException {
        if (content.length > maximum) {
            throw new IOException("source metadata exceeds its size limit");
        }
        Path parent = destination.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("source metadata parent is unavailable");
        }
        Path temporary = Files.createTempFile(parent, ".source-", ".tmp",
                PosixFilePermissions.asFileAttribute(visibility.file));
        try {
            Files.setPosixFilePermissions(temporary, visibility.file);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void ensureDirectories(Path root, Path target, Visibility visibility) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path leaf = target.toAbsolutePath().normalize();
        if (!leaf.startsWith(base)) {
            throw new IOException("source directory lies outside its configured root");
        }
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(base, PosixFilePermissions.asFileAttribute(visibility.directory));
        }
        setDirectoryPermission(base, visibility);
        Path current = base;
        for (Path segment : base.relativize(leaf)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current, PosixFilePermissions.asFileAttribute(visibility.directory));
            }
            setDirectoryPermission(current, visibility);
        }
        forceDirectory(leaf);
        if (!leaf.equals(base)) {
            forceDirectory(leaf.getParent());
        }
    }

    private static void setDirectoryPermission(Path directory, Visibility visibility) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("source directory is not a regular directory");
        }
        Files.setPosixFilePermissions(directory, visibility.directory);
    }

    public static void preparePublishedTree(Path staging) throws IOException {
        Files.walkFileTree(staging, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (!attributes.isDirectory()) {
                    throw new IOException("staged source directory is invalid");
                }
                Files.setPosixFilePermissions(directory, Visibility.PUBLISHED.directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) {
                    throw new IOException("staged source file is invalid");
                }
                Files.setPosixFilePermissions(file, Visibility.PUBLISHED.file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    public static byte[] boundedRead(Path path, long maximum) throws IOException {
        if (Files.size(path) > maximum) {
            throw new IOException("source metadata exceeds its size limit");
        }
        byte[] data = Files.readAllBytes(path);
        if (data.length > maximum) {
            throw new IOException("source metadata exceeds its size limit");
        }
        return data;
    }

    public static void forceTree(Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) {
                    throw new IOException("sealed tree contains a nonregular file");
                }
                try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
                    file.force(true);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (Objects.nonNull(failure)) {
                    throw failure;
                }
                forceDirectory(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    public static void forceDirectory(Path directory) throws IOException {
        try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) {
            file.force(true);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
