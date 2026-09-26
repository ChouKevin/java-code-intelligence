package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.repository.application.RepositoryMutationException;
import org.eclipse.jgit.api.Git;
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

    public static Git open(Path workingTree) {
        Path root = Objects.requireNonNull(workingTree, "working tree is required").toAbsolutePath().normalize();
        Repository repository = null;
        try {
            requireCanonicalDirectory(root, "working tree");
            Path gitDirectory = root.resolve(".git");
            requireGitDirectory(root, gitDirectory);
            validateControlMetadata(root, gitDirectory);
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

    private static void validateControlMetadata(Path root, Path gitDirectory) throws IOException {
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
        config.fromText(Files.readString(path, StandardCharsets.UTF_8));
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
}
