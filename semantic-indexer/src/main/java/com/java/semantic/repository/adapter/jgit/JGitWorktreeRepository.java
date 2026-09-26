package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.repository.application.RepositoryMutationException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/** Validates and opens only the configured checkout's own Git control directory. */
public final class JGitWorktreeRepository {

    private JGitWorktreeRepository() {
    }

    public static boolean isCloned(Path workingTree) {
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            requireCanonicalDirectory(root, "working tree");
            Path gitDirectory = root.resolve(".git");
            if (!Files.exists(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            requireGitDirectory(root, gitDirectory);
            return true;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("managed Git checkout boundary is invalid", exception);
        }
    }

    public static Git open(Path workingTree, JdtLsProperties properties) {
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        Repository repository = null;
        try {
            validateExistingCheckout(root, properties);
            Path gitDirectory = root.resolve(".git");
            repository = new FileRepositoryBuilder()
                    .setGitDir(gitDirectory.toFile())
                    .setWorkTree(root.toFile())
                    .setMustExist(true)
                    .build();
            Path openedGitDirectory = repository.getDirectory().toPath().toRealPath();
            Path openedWorkingTree = repository.getWorkTree().toPath().toRealPath();
            if (repository.isBare() || !gitDirectory.equals(openedGitDirectory)
                    || !root.equals(openedWorkingTree)) {
                throw new IOException("JGit resolved a different worktree or Git directory");
            }
            return new Git(repository);
        } catch (IOException | RuntimeException exception) {
            if (Objects.nonNull(repository)) {
                try {
                    repository.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            if (exception instanceof RepositoryMutationException mutationException) {
                throw mutationException;
            }
            throw new RepositoryMutationException("managed Git control metadata is invalid or redirected", exception);
        }
    }

    private static void requireCanonicalDirectory(Path directory, String description) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.equals(directory.toRealPath())) {
            throw new IOException(description + " must be a canonical real directory");
        }
    }

    private static void requireGitDirectory(Path root, Path gitDirectory) throws IOException {
        if (!Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(gitDirectory)) {
            throw new IOException("checkout must contain a real .git directory");
        }
        Path realGitDirectory = gitDirectory.toRealPath();
        if (!gitDirectory.equals(realGitDirectory) || !realGitDirectory.startsWith(root)) {
            throw new IOException("Git directory escaped its configured worktree");
        }
    }

    private static void validateControlMetadata(
            Path root,
            Path gitDirectory,
            JdtLsProperties properties,
            long applicationUid,
            boolean requireNonWritable) throws IOException {
        boolean linuxUid = properties.getIsolationMode() == JdtLsProperties.IsolationMode.LINUX_UID;
        try (Stream<Path> entries = Files.walk(gitDirectory)) {
            Iterator<Path> iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Git control metadata contains a symbolic link");
                }
                Path realEntry = entry.toRealPath();
                if (!realEntry.startsWith(gitDirectory) || !realEntry.startsWith(root)) {
                    throw new IOException("Git control metadata escaped its configured directory");
                }
                if (linuxUid) {
                    validateAuthoritativeControlEntry(entry, applicationUid, properties, requireNonWritable);
                }
            }
        }

        rejectIfPresent(gitDirectory.resolve("gitdir"));
        rejectIfPresent(gitDirectory.resolve("commondir"));
        rejectIfPresent(gitDirectory.resolve("config.worktree"));
        rejectIfPresent(gitDirectory.resolve("objects/info/alternates"));
        rejectIfPresent(gitDirectory.resolve("objects/info/http-alternates"));
        validateRepositoryConfig(gitDirectory.resolve("config"));
    }

    private static void rejectIfPresent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git control metadata uses an unsupported indirection");
        }
    }

    private static void validateRepositoryConfig(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git repository config is missing or invalid");
        }
        Config config = new Config();
        try {
            config.fromText(Files.readString(path, StandardCharsets.UTF_8));
        } catch (ConfigInvalidException exception) {
            throw new IOException("Git repository config is malformed", exception);
        }
        if (Objects.nonNull(config.getString("core", null, "worktree"))
                || config.getBoolean("core", null, "bare", false)
                || config.getBoolean("extensions", null, "worktreeConfig", false)
                || hasValues(config.getStringList("include", null, "path"))
                || config.getSubsections("includeIf").stream()
                        .anyMatch(section -> hasValues(config.getStringList("includeIf", section, "path")))
                || config.getSubsections("url").stream()
                        .anyMatch(section -> hasValues(config.getStringList("url", section, "insteadOf")))) {
            throw new IOException("Git config contains a worktree or remote redirection");
        }
    }

    private static boolean hasValues(String[] values) {
        return Arrays.stream(values).anyMatch(StringUtils::hasText);
    }
    public static void validateManagedCheckoutAuthorityChain(Path workingTree, JdtLsProperties properties)
            throws IOException {
        JdtLsProperties policy = Objects.requireNonNull(properties, "JDT LS properties are required");
        if (policy.getIsolationMode() != JdtLsProperties.IsolationMode.LINUX_UID) {
            return;
        }
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        long applicationUid = applicationUid(policy);
        Path protectedDirectory = Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? root : root.getParent();
        if (Objects.isNull(protectedDirectory)) {
            throw new IOException("managed checkout has no trusted parent");
        }
        requireCanonicalDirectory(protectedDirectory, "managed checkout authority");
        validateAuthorityDirectoryChain(protectedDirectory, policy);
        requireApplicationOwnedNonWritable(protectedDirectory, applicationUid, policy);
    }

    public static void validateNewCheckoutDestination(Path workingTree, JdtLsProperties properties)
            throws IOException {
        JdtLsProperties policy = Objects.requireNonNull(properties, "JDT LS properties are required");
        if (policy.getIsolationMode() != JdtLsProperties.IsolationMode.LINUX_UID) {
            return;
        }
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("new managed checkout destination already exists");
        }
        Path parent = root.getParent();
        if (Objects.isNull(parent)) {
            throw new IOException("new managed checkout has no parent");
        }
        requireCanonicalDirectory(parent, "managed checkout parent");
        long applicationUid = applicationUid(policy);
        validateAuthorityDirectoryChain(parent, policy);
        requireApplicationOwnedNonWritable(parent, applicationUid, policy);
    }

    public static void prepareNewCheckout(Path workingTree, JdtLsProperties properties) throws IOException {
        JdtLsProperties policy = Objects.requireNonNull(properties, "JDT LS properties are required");
        if (policy.getIsolationMode() != JdtLsProperties.IsolationMode.LINUX_UID) {
            return;
        }
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        Path parent = root.getParent();
        if (Objects.isNull(parent)) {
            throw new IOException("new managed checkout has no parent");
        }
        requireCanonicalDirectory(parent, "managed checkout parent");
        long applicationUid = applicationUid(policy);
        validateAuthorityDirectoryChain(parent, policy);
        requireApplicationOwnedNonWritable(parent, applicationUid, policy);
        requireCanonicalDirectory(root, "new managed checkout");
        requireApplicationOwned(root, applicationUid);
        Path gitDirectory = root.resolve(".git");
        requireGitDirectory(root, gitDirectory);
        validateControlMetadata(root, gitDirectory, policy, applicationUid, false);

        try (Stream<Path> entries = Files.walk(gitDirectory)) {
            for (Path entry : entries.toList()) {
                setUnixMode(entry, unixMode(entry) & ~0022);
            }
        }
        setUnixMode(root, (unixMode(root) & 01000) | 0755);
        validateExistingCheckout(root, policy);
    }

    public static void validateManagedCheckoutOwnership(Path workingTree, JdtLsProperties properties)
            throws IOException {
        JdtLsProperties policy = Objects.requireNonNull(properties, "JDT LS properties are required");
        if (policy.getIsolationMode() == JdtLsProperties.IsolationMode.LINUX_UID) {
            Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
            validateExistingCheckout(root, policy);
        }
    }

    private static void validateExistingCheckout(Path root, JdtLsProperties properties) throws IOException {
        JdtLsProperties policy = Objects.requireNonNull(properties, "JDT LS properties are required");
        requireCanonicalDirectory(root, "working tree");
        Path gitDirectory = root.resolve(".git");
        requireGitDirectory(root, gitDirectory);
        long applicationUid = -1;
        if (policy.getIsolationMode() == JdtLsProperties.IsolationMode.LINUX_UID) {
            applicationUid = applicationUid(policy);
            validateAuthorityDirectoryChain(root, policy);
            requireApplicationOwnedNonWritable(root, applicationUid, policy);
        }
        validateControlMetadata(root, gitDirectory, policy, applicationUid, true);
    }

    private static long applicationUid(JdtLsProperties properties) throws IOException {
        Path processDirectory = Path.of("/proc/self").toRealPath();
        long applicationUid = unixLong(processDirectory, "uid");
        if (applicationUid == properties.getAnalysisUid() || properties.getAnalysisUid() == 0) {
            throw new IOException("Indexer and analysis identities must be distinct and non-root");
        }
        return applicationUid;
    }

    private static void validateAuthorityDirectoryChain(Path directory, JdtLsProperties properties)
            throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path current = normalized.getRoot();
        if (Objects.isNull(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed checkout authority has no real root");
        }
        for (Path segment : normalized) {
            Path child = current.resolve(segment);
            if (Files.isSymbolicLink(child)
                    || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    || !child.equals(child.toRealPath())) {
                throw new IOException("managed checkout authority contains a noncanonical directory");
            }
            validateDirectoryEntryBoundary(current, child, properties);
            current = child;
        }
    }

    private static void validateDirectoryEntryBoundary(
            Path parent,
            Path entry,
            JdtLsProperties properties) throws IOException {
        long analysisUid = properties.getAnalysisUid();
        long parentUid = unixLong(parent, "uid");
        long entryUid = unixLong(entry, "uid");
        int parentMode = unixMode(parent);
        boolean analysisOwnsParent = parentUid == analysisUid;
        boolean analysisCanWriteParent = analysisCanWrite(parent, properties);
        boolean sticky = (parentMode & 01000) != 0;
        boolean stickyProtectsEntry = sticky
                && analysisUid != 0
                && !analysisOwnsParent
                && entryUid != analysisUid;
        if (analysisOwnsParent || (analysisCanWriteParent && !stickyProtectsEntry)) {
            throw new IOException("analysis identity can replace a managed checkout authority entry");
        }
    }

    private static void requireApplicationOwnedNonWritable(
            Path path,
            long applicationUid,
            JdtLsProperties properties) throws IOException {
        requireApplicationOwned(path, applicationUid);
        if (analysisCanWrite(path, properties)) {
            throw new IOException("analysis identity can write a protected managed checkout path");
        }
    }

    private static void requireApplicationOwned(Path path, long applicationUid) throws IOException {
        if (unixLong(path, "uid") != applicationUid) {
            throw new IOException("managed checkout path is not application-owned");
        }
    }

    private static boolean analysisCanWrite(Path path, JdtLsProperties properties) throws IOException {
        long analysisUid = properties.getAnalysisUid();
        if (analysisUid == 0) {
            return true;
        }
        long ownerUid = unixLong(path, "uid");
        long groupId = unixLong(path, "gid");
        int mode = unixMode(path);
        if (ownerUid == analysisUid) {
            return (mode & 0200) != 0;
        }
        if (groupId == properties.getAnalysisGid()) {
            return (mode & 0020) != 0;
        }
        return (mode & 0002) != 0;
    }

    private static void validateAuthoritativeControlEntry(
            Path entry,
            long applicationUid,
            JdtLsProperties properties,
            boolean requireNonWritable) throws IOException {
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git control metadata contains an unsupported filesystem entry");
        }
        requireApplicationOwned(entry, applicationUid);
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                && unixLong(entry, "nlink") != 1) {
            throw new IOException("Git control metadata contains a hard-linked file");
        }
        if (requireNonWritable && analysisCanWrite(entry, properties)) {
            throw new IOException("analysis identity can write Git control metadata");
        }
    }

    private static long unixLong(Path path, String attribute) throws IOException {
        try {
            Object value = Files.getAttribute(path, "unix:" + attribute, LinkOption.NOFOLLOW_LINKS);
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IOException("LINUX_UID requires numeric unix:" + attribute + " attributes");
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            throw new IOException("LINUX_UID requires supported unix attributes for managed paths", exception);
        }
    }

    private static int unixMode(Path path) throws IOException {
        return (int) unixLong(path, "mode");
    }

    private static void setUnixMode(Path path, int mode) throws IOException {
        try {
            Files.setAttribute(path, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
        } catch (IllegalArgumentException | UnsupportedOperationException exception) {
            throw new IOException("LINUX_UID requires supported unix mode attributes for managed paths", exception);
        }
    }

}
