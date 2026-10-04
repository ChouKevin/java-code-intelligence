package com.java.semantic.indexer.source;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.model.repository.RepositoryId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Test-owned tracked Git source and fresh private/published roots. */
public final class LocalSourceFixture implements AutoCloseable {
    public final Path root;
    public final Path remote;
    public final Path admin;
    public final Path published;
    public final Git git;
    public final RepositoryId repository = new RepositoryId("orders");

    public LocalSourceFixture(Path root) throws Exception {
        this.root = root;
        remote = root.resolve("remote");
        admin = root.resolve("source-admin");
        published = root.resolve("source-published");
        git = Git.init().setInitialBranch("main").setDirectory(remote.toFile()).call();
    }

    public String commit(String path, byte[] exact, String message) throws Exception {
        Path target = remote.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, exact);
        git.add().addFilepattern(path).call();
        return git.commit().setAuthor("Source fixture", "fixture@example.test").setMessage(message).call().getId().name();
    }

    public ConfigurableApplicationContext start() {
        SpringApplication application = new SpringApplication(SemanticIndexerApplication.class);
        return application.run("--server.port=0", "--server.address=127.0.0.1",
                "--semantic.indexer.admin-token=admin-secret", "--semantic.source-admin-root=" + admin,
                "--semantic.source-published-root=" + published,
                "--semantic.repositories.orders.url=" + remote.toUri(),
                "--semantic.repositories.orders.display-name=Orders",
                "--semantic.repositories.orders.default-branch=main",
                "--semantic.repositories.orders.project-guide-path=GUIDE.md");
    }


    @Override
    public void close() {
        git.close();
    }
}
