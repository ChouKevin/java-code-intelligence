package com.java.semantic.indexer.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/** Fetches only approved remote heads into a private bare object database, never a checkout. */
@Component
public final class RepositoryRevisionResolver {
    private final RepositoryRegistry registry;
    private final RepositoryProperties properties;
    private final Path adminRoot;

    public RepositoryRevisionResolver(RepositoryRegistry registry, RepositoryProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.adminRoot = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
    }

    public Path barePath(RepositoryId repository) {
        return adminRoot.resolve("repositories").resolve(repository.value()).resolve("repository.git");
    }

    public RepositoryRevision resolve(RepositoryId repository, Optional<RepositoryRevision> requested) {
        RepositoryProperties.RepositoryConfig config = registry.require(repository);
        try (Git git = open(repository)) {
            if (requested.isPresent() && commitExists(git, requested.orElseThrow())) {
                return requested.orElseThrow();
            }
            String branch = config.getDefaultBranch();
            String local = "refs/source-heads/" + branch;
            RefSpec spec = requested.isPresent()
                    ? new RefSpec("+refs/heads/*:refs/source-heads/*")
                    : new RefSpec("+refs/heads/" + branch + ":" + local);
            org.eclipse.jgit.api.FetchCommand fetch = git.fetch().setRemote(registry.endpoint(repository))
                    .setRefSpecs(spec).setRemoveDeletedRefs(false).setCheckFetchedObjects(true)
                    .setRecurseSubmodules(org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS).setTimeout(30);
            if (!properties.getGitToken().isBlank()) {
                fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                        properties.getGitUsername(), properties.getGitToken()));
            }
            fetch.call();
            if (requested.isPresent()) {
                RepositoryRevision exact = requested.orElseThrow();
                if (!commitExists(git, exact)) {
                    throw new IllegalArgumentException("requested commit is not available from the approved repository");
                }
                return exact;
            }
            Ref head = git.getRepository().exactRef(local);
            if (java.util.Objects.isNull(head) || java.util.Objects.isNull(head.getObjectId())) {
                throw new IOException("configured branch does not resolve to a commit");
            }
            RepositoryRevision resolved = RepositoryRevision.ofSha(head.getObjectId().name());
            if (!commitExists(git, resolved)) {
                throw new IOException("configured branch is not a commit");
            }
            return resolved;
        } catch (IOException | GitAPIException exception) {
            throw new IllegalStateException("approved Git revision unavailable", exception);
        }
    }

    public Git open(RepositoryId repository) throws IOException {
        registry.require(repository);
        Path path = barePath(repository);
        DurableSourceFiles.ensureDirectories(adminRoot, path.getParent(),
                DurableSourceFiles.Visibility.PRIVATE);
        if (!Files.exists(path)) {
            try (Git created = Git.init().setBare(true).setDirectory(path.toFile()).call()) {
                DurableSourceFiles.forceDirectory(path.getParent());
            } catch (GitAPIException exception) {
                throw new IOException("private object database unavailable", exception);
            }
        }
        DurableSourceFiles.ensureDirectories(adminRoot, path,
                DurableSourceFiles.Visibility.PRIVATE);
        return new Git(new FileRepositoryBuilder().setGitDir(path.toFile()).setBare().build());
    }

    private static boolean commitExists(Git git, RepositoryRevision revision) throws IOException {
        ObjectId id = ObjectId.fromString(revision.value());
        if (!git.getRepository().getObjectDatabase().has(id)) {
            return false;
        }
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(id);
            return commit.getId().equals(id);
        } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException exception) {
            return false;
        }
    }
}
