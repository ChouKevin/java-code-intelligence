package com.java.semantic.repository.adapter.jgit;

import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.config.JdtLsProperties;

import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitBranch;
import com.java.semantic.model.git.GitCommit;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitComparisonChange;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.TrackedSourceInventory;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.function.Consumer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.stream.Stream;

/** JGit 的唯一 production adapter */
@Component
public class JGitRepositoryAdapter implements GitRepositoryPort {

    private static final int PATCH_CHUNK_BYTES = 64 * 1024;

    private final RepositoryProperties properties;
    private final JdtLsProperties jdtLsProperties;

    public JGitRepositoryAdapter(RepositoryProperties properties, JdtLsProperties jdtLsProperties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.jdtLsProperties = Objects.requireNonNull(jdtLsProperties, "JDT LS properties are required");
    }

    @Override
    public boolean isCloned(Path workingTree) {
        return JGitWorktreeRepository.isCloned(workingTree);
    }

    @Override
    public RepositoryRevision clone(Path workingTree, String remoteUrl) {
        try {
            Path root = workingTree.toAbsolutePath().normalize();
            if (jdtLsProperties.getIsolationMode() == JdtLsProperties.IsolationMode.LINUX_UID) {
                return cloneLinuxUid(root, remoteUrl);
            }
            Path parent = root.getParent();
            if (Objects.nonNull(parent)) {
                Files.createDirectories(parent);
            }
            CloneCommand command = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(root.toFile())
                    .setTransportConfigCallback(ignoredTransport -> configureCloneMaintenance(root));
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

    private RepositoryRevision cloneLinuxUid(Path root, String remoteUrl) throws IOException, GitAPIException {
        JGitWorktreeRepository.validateNewCheckoutDestination(root, jdtLsProperties);
        Path staging = Files.createTempDirectory(
                root.getParent(),
                ".semantic-clone-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            CloneCommand command = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(staging.toFile())
                    .setTransportConfigCallback(ignoredTransport -> configureCloneMaintenance(staging));
            credentialsProvider().ifPresent(command::setCredentialsProvider);
            RepositoryRevision revision;
            try (Git git = command.call()) {
                JGitWorktreeRepository.prepareNewCheckout(staging, jdtLsProperties);
                revision = resolveHead(git);
            }
            Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
            return revision;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            discardFailedClone(staging, exception);
            throw exception;
        }
    }

    private static void configureCloneMaintenance(Path workingTree) {
        // CloneCommand owns its Repository internally; configure its fresh file before the first fetch.
        FileBasedConfig config = new FileBasedConfig(workingTree.resolve(".git/config").toFile(), FS.DETECTED);
        try {
            config.load();
            config.setBoolean("gc", null, "autoDetach", false);
            config.save();
        } catch (IOException | ConfigInvalidException exception) {
            throw new RepositoryMutationException("cannot configure serialized Git maintenance", exception);
        }
    }

    private static void discardFailedClone(Path staging, Exception failure) {
        try {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> entries = Files.walk(staging)) {
                    for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public void fetch(Path workingTree, String remoteUrl) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties)) {
            fetchRemote(git, remoteUrl);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("fetch failed", exception);
        }
    }

    @Override
    public void verifyComparisonEndpoints(Path workingTree, RepositoryRevision previous, RepositoryRevision current) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit previousCommit = walk.parseCommit(ObjectId.fromString(previous.value()));
            RevCommit currentCommit = walk.parseCommit(ObjectId.fromString(current.value()));
            List<RevCommit> trustedHeads = git.getRepository().getRefDatabase().getRefsByPrefix("refs/remotes/origin/").stream()
                    .filter(reference -> !reference.getName().equals("refs/remotes/origin/HEAD"))
                    .filter(reference -> Objects.nonNull(reference.getObjectId()))
                    .map(Ref::getObjectId)
                    .map(objectId -> parseTrustedHead(walk, objectId))
                    .toList();
            if (!reachableFromTrustedHead(walk, previousCommit, trustedHeads)
                    || !reachableFromTrustedHead(walk, currentCommit, trustedHeads)) {
                throw new RepositoryMutationException("comparison endpoint is not reachable from a trusted remote ref");
            }
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot validate comparison endpoints", exception);
        }
    }
    @Override
    public ResolvedReviewEndpoints resolveReviewEndpoints(Path workingTree, ReviewSelection selection) {
        ReviewSelection requested = Objects.requireNonNull(selection, "review selection is required");
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit after = walk.parseCommit(ObjectId.fromString(requested.afterRevision().value()));
            List<RevCommit> trustedHeads = git.getRepository().getRefDatabase().getRefsByPrefix("refs/remotes/origin/").stream()
                    .filter(reference -> !reference.getName().equals("refs/remotes/origin/HEAD"))
                    .filter(reference -> Objects.nonNull(reference.getObjectId()))
                    .map(Ref::getObjectId).map(objectId -> parseTrustedHead(walk, objectId)).toList();
            if (!reachableFromTrustedHead(walk, after, trustedHeads)) {
                throw new RepositoryMutationException("review after commit is not reachable from a trusted remote ref");
            }
            if (requested.kind() == ReviewComparisonType.RANGE) {
                RepositoryRevision before = requested.beforeRevision().orElseThrow();
                RevCommit beforeCommit = walk.parseCommit(ObjectId.fromString(before.value()));
                if (!reachableFromTrustedHead(walk, beforeCommit, trustedHeads)) {
                    throw new RepositoryMutationException("review before commit is not reachable from a trusted remote ref");
                }
                return new ResolvedReviewEndpoints(Optional.of(before), requested.afterRevision(), ReviewBaselineRule.DIRECT_RANGE);
            }
            if (after.getParentCount() == 0) {
                return new ResolvedReviewEndpoints(Optional.empty(), requested.afterRevision(), ReviewBaselineRule.EMPTY_TREE);
            }
            return new ResolvedReviewEndpoints(Optional.of(new RepositoryRevision(after.getParent(0).name())),
                    requested.afterRevision(), ReviewBaselineRule.FIRST_PARENT);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot resolve review endpoints", exception);
        }
    }


    @Override
    public void checkoutDetached(Path workingTree, RepositoryRevision revision) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties)) {
            git.checkout().setName(revision.value()).setForced(true).call();
            git.clean().setForce(true).setCleanDirectories(true).setIgnore(false)
                    .setPaths(Set.of("target", "build", ".classpath", ".factorypath", ".project", ".settings")).call();
            detachHead(git, revision);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("checkout failed", exception);
        }
    }

    @Override
    public RepositoryRevision currentRevision(Path workingTree) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties)) {
            return resolveHead(git);
        } catch (RepositoryMutationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot read HEAD", exception);
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

    @Override
    public List<GitBranch> fetchRemoteBranches(Path workingTree, String remoteUrl) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties)) {
            fetchRemote(git, remoteUrl);
            return git.getRepository().getRefDatabase().getRefsByPrefix("refs/remotes/origin/").stream()
                    .filter(reference -> !reference.getName().equals("refs/remotes/origin/HEAD"))
                    .filter(reference -> Objects.nonNull(reference.getObjectId()))
                    .map(reference -> new GitBranch(reference.getName().substring("refs/remotes/origin/".length()),
                            RepositoryRevision.ofSha(reference.getObjectId().getName())))
                    .sorted(java.util.Comparator.comparing(GitBranch::name)).toList();
        } catch (IOException | GitAPIException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot prepare remote branch catalog", exception);
        }
    }

    @Override
    public void streamReachableHistory(Path workingTree, RepositoryRevision revision, Consumer<GitCommit> consumer) {
        Consumer<GitCommit> requiredConsumer = Objects.requireNonNull(consumer, "history consumer is required");
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(ObjectId.fromString(revision.value()));
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(head);
            for (RevCommit commit : walk) {
                List<RepositoryRevision> parents = java.util.Arrays.stream(commit.getParents())
                        .map(parent -> RepositoryRevision.ofSha(parent.getId().getName())).toList();
                requiredConsumer.accept(new GitCommit(RepositoryRevision.ofSha(commit.getId().getName()), parents,
                        commit.getShortMessage(), Instant.ofEpochSecond(commit.getCommitTime())));
            }
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot prepare reachable history", exception);
        }
    }

    @Override
    public TrackedSourceInventory prepareSnapshot(Path workingTree, RepositoryRevision revision, SourceEvidencePolicy policy) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties);
                RevWalk walk = new RevWalk(git.getRepository())) {
            return snapshot(git.getRepository(), walk.parseCommit(ObjectId.fromString(revision.value())), policy);
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot prepare authorized Git snapshot", exception);
        }
    }

    @Override
    public GitPreparedComparison prepareComparison(Path workingTree, Optional<RepositoryRevision> previous, RepositoryRevision current,
            SourceEvidencePolicy previousPolicy, SourceEvidencePolicy currentPolicy,
            Set<String> availableGuidesBefore, Set<String> availableGuidesAfter) {
        try (Git git = JGitWorktreeRepository.open(workingTree, jdtLsProperties); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit previousCommit = previous.isPresent() ? walk.parseCommit(ObjectId.fromString(previous.orElseThrow().value())) : null;
            RevCommit currentCommit = walk.parseCommit(ObjectId.fromString(current.value()));
            List<GitSnapshotEntry> previousEntries = Objects.isNull(previousCommit) ? List.of()
                    : snapshot(git.getRepository(), previousCommit, previousPolicy).candidates().stream()
                            .filter(entry -> previousPolicy.allowsCode(entry.path()) || availableGuidesBefore.contains(entry.path())).toList();
            List<GitSnapshotEntry> currentEntries = snapshot(git.getRepository(), currentCommit, currentPolicy).candidates().stream()
                    .filter(entry -> currentPolicy.allowsCode(entry.path()) || availableGuidesAfter.contains(entry.path())).toList();
            List<GitComparisonChange> changes = changes(git.getRepository(), previousCommit, currentCommit,
                    previousPolicy, currentPolicy, availableGuidesBefore, availableGuidesAfter);
            GitComparisonAncestry relationship = Objects.isNull(previousCommit) ? GitComparisonAncestry.EMPTY_TREE
                    : ancestry(walk, previousCommit, currentCommit);
            return new GitPreparedComparison(previous, current, relationship, previousEntries, currentEntries, changes);
        } catch (IOException | RuntimeException exception) {
            throw new RepositoryMutationException("cannot prepare exact Git comparison", exception);
        }
    }

    private TrackedSourceInventory snapshot(org.eclipse.jgit.lib.Repository repository, RevCommit commit,
            SourceEvidencePolicy policy) throws IOException {
        List<GitSnapshotEntry> entries = new java.util.ArrayList<>();
        long storedTextBytes = 0L;
        long excluded = 0L;
        if (policy.projectGuidePath().isPresent()) {
            String guidePath = policy.projectGuidePath().orElseThrow();
            try (TreeWalk guide = TreeWalk.forPath(repository, guidePath, commit.getTree())) {
                if (Objects.nonNull(guide) && FileMode.TREE.equals(guide.getFileMode(0))) {
                    entries.add(new GitSnapshotEntry(guidePath, FileMode.TREE.toString(), guide.getObjectId(0).name(),
                            GitFileContentStatus.UNSUPPORTED_PATH, 0L, new byte[0]));
                }
            }
        }
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                FileMode mode = walk.getFileMode(0);
                SnapshotPath snapshotPath = snapshotPath(walk.getRawPath());
                if (!snapshotPath.supported() || !SourceEvidencePolicy.validPath(snapshotPath.value())
                        || !(policy.allowsCode(snapshotPath.value()) || policy.allowsGuide(snapshotPath.value()))) {
                    excluded++;
                    continue;
                }
                if (FileMode.GITLINK.equals(mode)) {
                    if (policy.allowsGuide(snapshotPath.value())) {
                        entries.add(new GitSnapshotEntry(snapshotPath.value(), mode.toString(), walk.getObjectId(0).name(),
                                GitFileContentStatus.SUBMODULE, 0L, new byte[0]));
                    } else {
                        excluded++;
                    }
                    continue;
                }
                ObjectLoader loader = repository.open(walk.getObjectId(0));
                long size = loader.getSize();
                if (FileMode.SYMLINK.equals(mode)) {
                    if (policy.allowsGuide(snapshotPath.value())) {
                        entries.add(new GitSnapshotEntry(snapshotPath.value(), mode.toString(), walk.getObjectId(0).name(),
                                GitFileContentStatus.SYMLINK, size, new byte[0]));
                    } else {
                        excluded++;
                    }
                    continue;
                }
                if (size > properties.getGitEvidenceFileTextBytes()) {
                    entries.add(new GitSnapshotEntry(snapshotPath.value(), mode.toString(), walk.getObjectId(0).name(), GitFileContentStatus.TOO_LARGE, size, new byte[0]));
                    continue;
                }
                byte[] bytes = loader.getBytes();
                GitFileContentStatus contentStatus = status(mode, bytes);
                boolean codeText = contentStatus == GitFileContentStatus.TEXT && policy.allowsCode(snapshotPath.value());
                if (codeText && exceedsSnapshotLimit(storedTextBytes, size)) {
                    throw new RepositoryMutationException("exact snapshot text budget exceeded");
                }
                if (contentStatus == GitFileContentStatus.TEXT) {
                    if (codeText) {
                        storedTextBytes += size;
                    }
                    entries.add(new GitSnapshotEntry(snapshotPath.value(), mode.toString(), walk.getObjectId(0).name(), contentStatus, size, bytes));
                    continue;
                }
                entries.add(new GitSnapshotEntry(snapshotPath.value(), mode.toString(), walk.getObjectId(0).name(), contentStatus, size, new byte[0]));
            }
        }
        return new TrackedSourceInventory(entries, excluded);
    }

    private List<GitComparisonChange> changes(org.eclipse.jgit.lib.Repository repository, RevCommit previous, RevCommit current,
            SourceEvidencePolicy previousPolicy, SourceEvidencePolicy currentPolicy,
            Set<String> availableGuidesBefore, Set<String> availableGuidesAfter) throws IOException {
        List<GitComparisonChange> changes = new java.util.ArrayList<>();
        try (ChunkingOutputStream output = new ChunkingOutputStream(); DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(repository);
            long ordinal = 0L;
            for (DiffEntry entry : diffEntries(repository, previous, current)) {
                output.reset();
                SnapshotPath oldPath = comparisonPath(entry.getOldPath());
                SnapshotPath newPath = comparisonPath(entry.getNewPath());
                if (!oldPath.supported() || !newPath.supported()
                        || entry.getOldMode().equals(FileMode.SYMLINK) || entry.getNewMode().equals(FileMode.SYMLINK)
                        || entry.getOldMode().equals(FileMode.GITLINK) || entry.getNewMode().equals(FileMode.GITLINK)
                        || !SourceEvidencePolicy.allowsChange(
                                entry.getOldMode().equals(FileMode.MISSING) ? Optional.empty() : Optional.of(oldPath.value()),
                                previousPolicy, availableGuidesBefore,
                                entry.getNewMode().equals(FileMode.MISSING) ? Optional.empty() : Optional.of(newPath.value()),
                                currentPolicy, availableGuidesAfter)) {
                    continue;
                }
                String diffStatus = !oldPath.supported() || !newPath.supported() ? GitFileContentStatus.UNSUPPORTED_PATH.name() : diffStatus(repository, entry);
                List<String> patchChunks = List.of();
                if ("AVAILABLE".equals(diffStatus)) {
                    formatter.format(entry);
                    patchChunks = output.chunks();
                    if (patchChunks.isEmpty() && output.hasBytes()) {
                        diffStatus = "TOO_LARGE";
                    }
                }
                changes.add(new GitComparisonChange("c-" + ordinal, changeKind(entry), oldPath.value(), newPath.value(),
                        entry.getOldMode().toString(), entry.getNewMode().toString(), entry.getOldId().name(), entry.getNewId().name(), patchChunks,
                        diffStatus, oldPath.rawPath(), newPath.rawPath()));
                ordinal++;
            }
        }
        return List.copyOf(changes);
    }

    /** Uses JGit's raw TreeWalk only to retain path identity before DiffEntry renders it. */
    private static List<DiffEntry> diffEntries(org.eclipse.jgit.lib.Repository repository, RevCommit previous, RevCommit current) throws IOException {
        try (RawPathTreeWalk walk = new RawPathTreeWalk(repository)) {
            if (Objects.isNull(previous)) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(previous.getTree());
            }
            walk.addTree(current.getTree());
            walk.setRecursive(true);
            walk.setFilter(TreeFilter.ANY_DIFF);
            RenameDetector detector = new RenameDetector(repository);
            detector.setRenameScore(60);
            detector.addAll(DiffEntry.scan(walk));
            return detector.compute();
        }
    }

    private String diffStatus(org.eclipse.jgit.lib.Repository repository, DiffEntry entry) throws IOException {
        GitFileContentStatus oldStatus = entryStatus(repository, entry.getOldId().toObjectId(), entry.getOldMode());
        GitFileContentStatus newStatus = entryStatus(repository, entry.getNewId().toObjectId(), entry.getNewMode());
        if (oldStatus != GitFileContentStatus.TEXT) {
            return oldStatus.name();
        }
        if (newStatus != GitFileContentStatus.TEXT) {
            return newStatus.name();
        }
        return "AVAILABLE";
    }

    private GitFileContentStatus entryStatus(org.eclipse.jgit.lib.Repository repository, ObjectId id, FileMode mode) throws IOException {
        if (ObjectId.zeroId().equals(id)) {
            return GitFileContentStatus.TEXT;
        }
        if (FileMode.GITLINK.equals(mode)) {
            return GitFileContentStatus.SUBMODULE;
        }
        if (FileMode.SYMLINK.equals(mode)) {
            return GitFileContentStatus.SYMLINK;
        }
        ObjectLoader loader = repository.open(id);
        if (loader.getSize() > properties.getGitEvidenceFileTextBytes()) {
            return GitFileContentStatus.TOO_LARGE;
        }
        return status(mode, loader.getBytes());
    }

    private static GitComparisonAncestry ancestry(RevWalk walk, RevCommit previous, RevCommit current) throws IOException {
        if (previous.equals(current)) { return GitComparisonAncestry.SAME; }
        if (walk.isMergedInto(previous, current)) { return GitComparisonAncestry.PREVIOUS_ANCESTOR; }
        if (walk.isMergedInto(current, previous)) { return GitComparisonAncestry.CURRENT_ANCESTOR; }
        return GitComparisonAncestry.DIVERGED;
    }

    private static RevCommit parseTrustedHead(RevWalk walk, ObjectId objectId) {
        try {
            return walk.parseCommit(objectId);
        } catch (IOException exception) {
            throw new RepositoryMutationException("trusted remote ref is not a commit", exception);
        }
    }

    private static boolean reachableFromTrustedHead(RevWalk walk, RevCommit endpoint, List<RevCommit> trustedHeads) {
        for (RevCommit trustedHead : trustedHeads) {
            try {
                if (walk.isMergedInto(endpoint, trustedHead)) {
                    return true;
                }
            } catch (IOException exception) {
                throw new RepositoryMutationException("cannot validate comparison endpoint reachability", exception);
            }
        }
        return false;
    }

    private static GitChangeKind changeKind(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case ADD -> GitChangeKind.ADD;
            case DELETE -> GitChangeKind.DELETE;
            case RENAME, COPY -> GitChangeKind.RENAME;
            case MODIFY -> entry.getOldMode().equals(entry.getNewMode()) ? GitChangeKind.MODIFY : GitChangeKind.MODE;
        };
    }

    private static GitFileContentStatus status(FileMode mode, byte[] bytes) {
        if (FileMode.SYMLINK.equals(mode)) { return GitFileContentStatus.SYMLINK; }
        if (FileMode.GITLINK.equals(mode)) { return GitFileContentStatus.SUBMODULE; }
        if (containsNul(bytes)) { return GitFileContentStatus.BINARY; }
        String text = strictUtf8(bytes);
        if (text.isEmpty() && bytes.length > 0) { return GitFileContentStatus.UNSUPPORTED_ENCODING; }
        if (text.matches("version https://git-lfs\\.github\\.com/spec/v1\\noid sha256:[0-9a-f]{64}\\nsize [0-9]+\\n?")) {
            return GitFileContentStatus.LFS_POINTER;
        }
        return GitFileContentStatus.TEXT;
    }

    private static SnapshotPath snapshotPath(byte[] rawPath) {
        String path = strictUtf8(rawPath);
        if (!path.isEmpty() || rawPath.length == 0) {
            return new SnapshotPath(path, true, rawPath.clone());
        }
        return new SnapshotPath("\u0000raw-path-hex:" + java.util.HexFormat.of().formatHex(rawPath), false, rawPath.clone());
    }

    private static SnapshotPath comparisonPath(String rendered) {
        if (DiffEntry.DEV_NULL.equals(rendered)) {
            return new SnapshotPath("", true, new byte[0]);
        }
        String marker = "\u0000raw-path-hex:";
        if (rendered.startsWith(marker)) {
            try {
                return snapshotPath(java.util.HexFormat.of().parseHex(rendered.substring(marker.length())));
            } catch (IllegalArgumentException exception) {
                throw new RepositoryMutationException("JGit produced an invalid raw path marker", exception);
            }
        }
        return snapshotPath(rendered.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean containsNul(byte[] bytes) { for (byte value : bytes) { if (value == 0) { return true; } } return false; }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            return "";
        }
    }

    private record SnapshotPath(String value, boolean supported, byte[] rawPath) {
        private SnapshotPath { rawPath = rawPath.clone(); }
        @Override public byte[] rawPath() { return rawPath.clone(); }
    }

    private static final class RawPathTreeWalk extends TreeWalk {
        private RawPathTreeWalk(org.eclipse.jgit.lib.Repository repository) {
            super(repository);
        }

        @Override
        public String getPathString() {
            return snapshotPath(getRawPath()).value();
        }
    }

    private boolean exceedsSnapshotLimit(long storedTextBytes, long size) {
        try {
            return Math.addExact(storedTextBytes, size) > properties.getGitEvidenceSnapshotTextBytes();
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private static final class ChunkingOutputStream extends OutputStream {
        private final List<String> chunks = new java.util.ArrayList<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(PATCH_CHUNK_BYTES + 4);

        @Override
        public void write(int value) {
            pending.write(value);
            drainCompleteChunks();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            pending.write(bytes, offset, length);
            drainCompleteChunks();
        }

        private List<String> chunks() {
            byte[] remaining = pending.toByteArray();
            if (remaining.length > 0) {
                appendChunk(remaining, remaining.length);
                pending.reset();
            }
            return List.copyOf(chunks);
        }

        private boolean hasBytes() {
            return !chunks.isEmpty() || pending.size() > 0;
        }

        private void reset() {
            chunks.clear();
            pending.reset();
        }

        private void drainCompleteChunks() {
            byte[] bytes = pending.toByteArray();
            while (bytes.length > PATCH_CHUNK_BYTES) {
                int end = PATCH_CHUNK_BYTES;
                while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
                    end--;
                }
                if (end == 0) {
                    throw new RepositoryMutationException("JGit produced an invalid UTF-8 patch");
                }
                appendChunk(bytes, end);
                pending.reset();
                pending.write(bytes, end, bytes.length - end);
                bytes = pending.toByteArray();
            }
        }

        private void appendChunk(byte[] bytes, int length) {
            String chunk = strictUtf8(java.util.Arrays.copyOf(bytes, length));
            if (chunk.isEmpty() && length > 0) {
                throw new RepositoryMutationException("JGit produced an invalid UTF-8 patch");
            }
            chunks.add(chunk);
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

    private void fetchRemote(Git git, String remoteUrl) throws GitAPIException {
        // Detached GC must not outlive this operation and race the next checked metadata traversal.
        git.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false);
        FetchCommand fetch = git.fetch()
                .setRemote(remoteUrl)
                .setRefSpecs(
                        new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                        new RefSpec("+refs/tags/*:refs/tags/*"))
                .setRemoveDeletedRefs(true);
        credentialsProvider().ifPresent(fetch::setCredentialsProvider);
        fetch.call();
    }

    private static boolean matchesAdvertisedRef(Ref reference, String ref) {
        if (ref.startsWith("refs/")) {
            return ref.equals(reference.getName());
        }
        return ref.equals(reference.getName()) || ("refs/heads/" + ref).equals(reference.getName())
                || ("refs/tags/" + ref).equals(reference.getName());
    }

    private RepositoryRevision resolveHead(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        if (Objects.isNull(head)) {
            throw new RepositoryMutationException("repository has no HEAD");
        }
        return RepositoryRevision.ofSha(head.getName());
    }

    private static void detachHead(Git git, RepositoryRevision revision) throws IOException {
        RefUpdate update = git.getRepository().updateRef("HEAD", true);
        update.setDetachingSymbolicRef();
        update.setNewObjectId(ObjectId.fromString(revision.value()));
        update.forceUpdate();
        Ref head = Objects.requireNonNull(git.getRepository().exactRef("HEAD"), "repository has no HEAD");
        ObjectId headRevision = Objects.requireNonNull(head.getObjectId(), "repository HEAD has no revision");
        if (head.isSymbolic() || !revision.value().equals(headRevision.getName())) {
            throw new RepositoryMutationException("repository HEAD was not detached at the requested revision");
        }
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
