package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/** JGit 的唯一 production adapter */
@Component
public class JGitRepositoryAdapter implements GitRepositoryPort {

    private final RepositoryProperties properties;

    public JGitRepositoryAdapter(RepositoryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public boolean isCloned(Path workingTree) {
        return Files.isDirectory(workingTree.resolve(".git"));
    }

    @Override
    public RepositoryRevision clone(Path workingTree, String url, String branch) {
        try {
            Path parent = workingTree.getParent();
            if (Objects.nonNull(parent)) {
                Files.createDirectories(parent);
            }
            CloneCommand command = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(workingTree.toFile())
                    .setBranch(branch);
            credentialsProvider().ifPresent(command::setCredentialsProvider);
            try (Git git = command.call()) {
                return resolveHead(git);
            }
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("clone failed", exception);
        }
    }

    @Override
    public RepositoryRevision fetchAndReset(Path workingTree, String branch) {
        try (Git git = Git.open(workingTree.toFile())) {
            FetchCommand fetch = git.fetch();
            credentialsProvider().ifPresent(fetch::setCredentialsProvider);
            fetch.call();
            ObjectId target = git.getRepository().resolve("refs/remotes/origin/" + branch);
            if (Objects.isNull(target)) {
                throw new RepositoryMutationException("remote branch was not found");
            }
            checkoutRevision(git, branch);
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(target.getName())
                    .call();
            return resolveHead(git);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("sync failed", exception);
        }
    }

    @Override
    public RepositoryRevision checkout(Path workingTree, String revision) {
        try (Git git = Git.open(workingTree.toFile())) {
            checkoutRevision(git, revision);
            return resolveHead(git);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("checkout failed", exception);
        }
    }

    @Override
    public RepositoryRevision currentRevision(Path workingTree) {
        try (Git git = Git.open(workingTree.toFile())) {
            return resolveHead(git);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot read HEAD", exception);
        }
    }

    @Override
    public String currentBranch(Path workingTree) {
        try (Git git = Git.open(workingTree.toFile())) {
            return git.getRepository().getBranch();
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot read current branch", exception);
        }
    }

    @Override
    public RepositoryRevision resolveRemoteRef(String remoteUrl, String ref) {
        try {
            LsRemoteCommand command = Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true).setTags(true);
            credentialsProvider().ifPresent(command::setCredentialsProvider);
            Collection<Ref> advertisedRefs = command.call();
            if (ref.matches("[0-9a-f]{40}")) {
                return resolveReachableCommit(remoteUrl, ObjectId.fromString(ref));
            }
            Optional<RepositoryRevision> advertisedRevision = advertisedRefs.stream()
                    .filter(reference -> matchesAdvertisedRef(reference, ref))
                    .findFirst()
                    .map(Ref::getObjectId)
                    .map(objectId -> resolveReachableCommit(remoteUrl, objectId));
            if (advertisedRevision.isPresent()) {
                return advertisedRevision.orElseThrow();
            }
            throw new RepositoryMutationException("remote ref was not found");
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("remote revision selection failed", exception);
        }
    }

    private RepositoryRevision resolveReachableCommit(String remoteUrl, ObjectId revision) {
        DfsRepositoryDescription description = new DfsRepositoryDescription("remote-revision-validation");
        InMemoryRepository.Builder builder = new InMemoryRepository.Builder()
                .setRepositoryDescription(description)
                .setFS(FS.DETECTED);
        try (InMemoryRepository repository = builder.build();
             Git git = new Git(repository)) {
            FetchCommand fetch = git.fetch()
                    .setRemote(remoteUrl)
                    .setRefSpecs(
                            new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                            new RefSpec("+refs/tags/*:refs/tags/*"));
            credentialsProvider().ifPresent(fetch::setCredentialsProvider);
            fetch.call();
            try (RevWalk walk = new RevWalk(repository)) {
                RevObject object = walk.parseAny(revision);
                RevObject peeled = walk.peel(object);
                return RepositoryRevision.ofSha(walk.parseCommit(peeled).getId().getName());
            }
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("remote revision selection failed", exception);
        }
    }

    private static boolean matchesAdvertisedRef(Ref reference, String ref) {
        return ref.equals(reference.getName()) || ("refs/heads/" + ref).equals(reference.getName())
                || ("refs/tags/" + ref).equals(reference.getName());
    }

    private boolean remoteBranchExists(Git git, String branch) throws IOException {
        return Objects.nonNull(git.getRepository().findRef("refs/remotes/origin/" + branch));
    }

    private void checkoutRevision(Git git, String revision)
            throws IOException, GitAPIException {
        boolean localBranchExists = Objects.nonNull(
                git.getRepository().findRef("refs/heads/" + revision));
        CheckoutCommand checkout = git.checkout().setName(revision);
        if (!localBranchExists && remoteBranchExists(git, revision)) {
            checkout.setCreateBranch(true)
                    .setStartPoint("origin/" + revision)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK);
        }
        checkout.call();
    }

    private RepositoryRevision resolveHead(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        if (Objects.isNull(head)) {
            throw new RepositoryMutationException("repository has no HEAD");
        }
        return RepositoryRevision.ofSha(head.getName());
    }

    private Optional<CredentialsProvider> credentialsProvider() {
        if (!StringUtils.hasText(properties.getGitToken())) {
            return Optional.empty();
        }
        String username = StringUtils.hasText(properties.getGitUsername())
                ? properties.getGitUsername()
                : "git";
        return Optional.of(new UsernamePasswordCredentialsProvider(
                username, properties.getGitToken()));
    }
}
