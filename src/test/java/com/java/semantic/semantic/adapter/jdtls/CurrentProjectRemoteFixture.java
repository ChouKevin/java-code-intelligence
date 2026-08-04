package com.java.semantic.semantic.adapter.jdtls;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

/** 為垂直整合測試建立目前專案 production 原始碼的暫時 Git remote */
final class CurrentProjectRemoteFixture implements AutoCloseable {

    private static final String BRANCH = "main";
    private static final PersonIdent TEST_IDENTITY = new PersonIdent(
            "Current Project Fixture", "current-project-fixture@example.test", new Date(0L), TimeZone.getTimeZone("UTC"));

    private final Path temporaryRoot;
    private final String remoteUri;
    private final String revision;

    private CurrentProjectRemoteFixture(Path temporaryRoot, String remoteUri, String revision) {
        this.temporaryRoot = Objects.requireNonNull(temporaryRoot, "temporaryRoot is required");
        this.remoteUri = Objects.requireNonNull(remoteUri, "remoteUri is required");
        this.revision = Objects.requireNonNull(revision, "revision is required");
    }

    static CurrentProjectRemoteFixture create() throws IOException, GitAPIException, URISyntaxException {
        Path temporaryRoot = Files.createTempDirectory("current-project-remote-");
        try {
            Path projectRoot = Path.of("").toAbsolutePath().normalize();
            Path seedRoot = temporaryRoot.resolve("seed");
            Path bareRemote = temporaryRoot.resolve("remote.git");
            copyProjectSources(projectRoot, seedRoot);

            try (Git ignoredBareRemote = Git.init().setBare(true).setDirectory(bareRemote.toFile()).call();
                 Git seed = Git.init().setInitialBranch(BRANCH).setDirectory(seedRoot.toFile()).call()) {
                seed.add().addFilepattern(".").call();
                String revision = seed.commit()
                        .setMessage("test fixture snapshot")
                        .setAuthor(TEST_IDENTITY)
                        .setCommitter(TEST_IDENTITY)
                        .call()
                        .getId()
                        .getName();
                String remoteUri = bareRemote.toUri().toString();
                seed.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(remoteUri))
                        .call();
                seed.push()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();
                return new CurrentProjectRemoteFixture(temporaryRoot, remoteUri, revision);
            }
        } catch (IOException | GitAPIException | URISyntaxException exception) {
            deleteTree(temporaryRoot);
            throw exception;
        }
    }

    String remoteUri() {
        return remoteUri;
    }

    String branch() {
        return BRANCH;
    }

    String revision() {
        return revision;
    }

    @Override
    public void close() throws IOException {
        deleteTree(temporaryRoot);
    }

    private static void copyProjectSources(Path projectRoot, Path seedRoot) throws IOException {
        Path pom = projectRoot.resolve("pom.xml");
        Path source = projectRoot.resolve("src/main");
        if (Files.isSymbolicLink(pom) || Files.isSymbolicLink(source)) {
            throw new IOException("project pom.xml and src/main must not be symbolic links");
        }
        Files.createDirectories(seedRoot);
        Files.copy(pom, seedRoot.resolve("pom.xml"));
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(seedRoot.resolve(projectRoot.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IOException("src/main must not contain symbolic links: " + file);
                }
                Files.copy(file, seedRoot.resolve(projectRoot.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (Objects.nonNull(exception)) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
