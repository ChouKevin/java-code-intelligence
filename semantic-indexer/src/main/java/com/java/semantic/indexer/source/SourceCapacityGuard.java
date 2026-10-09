package com.java.semantic.indexer.source;

import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Checkpoint admission for the logical size and available space of managed source storage. */
public final class SourceCapacityGuard {
    private static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";
    private static final String CAPACITY_UNAVAILABLE = "CAPACITY_UNAVAILABLE";

    private final RepositoryProperties properties;

    public SourceCapacityGuard(RepositoryProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public void check() {
        RepositoryProperties.SourceCapacityConfig capacity = properties.getSourceCapacity();
        long maximumManagedBytes = capacity.getMaxManagedBytes();
        long minimumFreeBytes = capacity.getMinFreeBytes();
        if (maximumManagedBytes == 0 && minimumFreeBytes == 0) {
            return;
        }

        try {
            Path adminRoot = managedRoot(properties.getSourceAdminRoot());
            Path publishedRoot = managedRoot(properties.getSourcePublishedRoot());
            Path indexRoot = managedRoot(properties.getIndexPublishedRoot());
            requireManagedDirectory(adminRoot);
            if (!adminRoot.equals(publishedRoot)) {
                requireManagedDirectory(publishedRoot);
            }
            boolean indexExists = Files.exists(indexRoot, LinkOption.NOFOLLOW_LINKS);
            requireManagedDirectory(indexExists ? indexRoot : indexRoot.getParent());
            if (minimumFreeBytes > 0) {
                checkMinimumFreeSpace(adminRoot, publishedRoot, indexExists ? indexRoot : indexRoot.getParent(), minimumFreeBytes);
            }
            if (maximumManagedBytes > 0) {
                checkMaximumManagedBytes(adminRoot, publishedRoot, indexExists ? indexRoot : null, maximumManagedBytes);
            }
        } catch (CapacityException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static Path managedRoot(String configuredRoot) {
        if (configuredRoot.isBlank()) {
            throw unavailable();
        }
        return Path.of(configuredRoot).toAbsolutePath().normalize();
    }

    private static void requireManagedDirectory(Path root) throws IOException {
        Path current = Objects.requireNonNull(root.getRoot(), "managed root must be absolute");
        requireDirectory(current);
        for (Path component : root) {
            current = current.resolve(component);
            requireDirectory(current);
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw unavailable();
        }
    }

    private static void checkMinimumFreeSpace(Path adminRoot, Path publishedRoot, Path indexRoot, long minimumFreeBytes)
            throws IOException {
        FileStore adminStore = Files.getFileStore(adminRoot);
        FileStore publishedStore = Files.getFileStore(publishedRoot);
        FileStore indexStore = Files.getFileStore(indexRoot);
        checkUsableSpace(adminStore, minimumFreeBytes);
        if (!adminStore.equals(publishedStore)) checkUsableSpace(publishedStore, minimumFreeBytes);
        if (!adminStore.equals(indexStore) && !publishedStore.equals(indexStore)) checkUsableSpace(indexStore, minimumFreeBytes);
    }

    private static void checkUsableSpace(FileStore store, long minimumFreeBytes) throws IOException {
        long usableBytes = store.getUsableSpace();
        if (usableBytes < 0) {
            throw unavailable();
        }
        if (usableBytes < minimumFreeBytes) {
            throw exceeded();
        }
    }

    private static void checkMaximumManagedBytes(Path adminRoot, Path publishedRoot, Path indexRoot, long maximumManagedBytes)
            throws IOException {
        ManagedBytesVisitor visitor = new ManagedBytesVisitor(maximumManagedBytes);
        Path[] roots = {adminRoot, publishedRoot, indexRoot};
        for (int index = 0; index < roots.length; index++) {
            Path root = roots[index];
            if (Objects.isNull(root)) continue;
            boolean covered = false;
            for (int other = 0; other < roots.length; other++) {
                if (other != index && Objects.nonNull(roots[other]) && root.startsWith(roots[other])
                        && (!root.equals(roots[other]) || other < index)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) Files.walkFileTree(root, visitor);
        }
    }

    private static CapacityException exceeded() {
        return new CapacityException(CAPACITY_EXCEEDED, "Configured source capacity would be exceeded.");
    }

    private static CapacityException unavailable() {
        return new CapacityException(CAPACITY_UNAVAILABLE, "Source capacity could not be safely measured.");
    }

    private static CapacityException unavailable(Throwable cause) {
        return new CapacityException(CAPACITY_UNAVAILABLE, "Source capacity could not be safely measured.", cause);
    }

    public static final class CapacityException extends RuntimeException {
        private final String failureCode;

        private CapacityException(String failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        private CapacityException(String failureCode, String message, Throwable cause) {
            super(message, cause);
            this.failureCode = failureCode;
        }

        public String failureCode() {
            return failureCode;
        }
    }

    private static final class ManagedBytesVisitor extends SimpleFileVisitor<Path> {
        private final long maximumManagedBytes;
        private long managedBytes;

        private ManagedBytesVisitor(long maximumManagedBytes) {
            this.maximumManagedBytes = maximumManagedBytes;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw unavailable();
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw unavailable();
            }
            long fileBytes = attributes.size();
            if (fileBytes < 0) {
                throw unavailable();
            }
            if (fileBytes > maximumManagedBytes - managedBytes) {
                throw exceeded();
            }
            managedBytes += fileBytes;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) {
            throw unavailable(exception);
        }
    }
}
