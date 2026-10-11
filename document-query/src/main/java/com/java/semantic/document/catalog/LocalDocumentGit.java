package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.config.DocumentStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Raw object insertion only: no porcelain, hooks, filters, checkout or network. */
final class LocalDocumentGit implements AutoCloseable {
    private final Repository repository;
    private final Path root;
    private final org.eclipse.jgit.storage.file.FileBasedConfig localConfig;
    LocalDocumentGit(DocumentStorage storage) throws IOException {
        Path directory = storage.gitDirectory();
        GitDocumentLoader.rejectSymlinkPath(directory);
        if (!directory.getFileName().toString().equals(".git") || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { throw unsupported(); }
        repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setMustExist(true).build();
        root = directory.getParent();
        localConfig = new org.eclipse.jgit.storage.file.FileBasedConfig(directory.resolve("config").toFile(), repository.getFS());
        try { supported(); }
        catch (RuntimeException | IOException exception) { repository.close(); throw exception; }
    }
    void supported() throws IOException {
        try { repository.getConfig().load(); localConfig.load(); }
        catch (org.eclipse.jgit.errors.ConfigInvalidException exception) { throw unsupported(); }
        Ref head = repository.exactRef(Constants.HEAD);
        if (repository.isBare() || repository.getRepositoryState() != RepositoryState.SAFE
                || !repository.getWorkTree().toPath().toAbsolutePath().normalize().equals(root)
                || Files.exists(repository.getDirectory().toPath().resolve("commondir"))
                || Objects.isNull(head) || !head.isSymbolic() || !head.getTarget().getName().startsWith(Constants.R_HEADS)
                || !repository.getConfig().getSubsections("remote").isEmpty()) { throw unsupported(); }
        for (String field : new String[] {"name", "email"}) {
            String value = localConfig.getString("user", null, field);
            if (Objects.isNull(value) || value.isBlank()) { throw unsupported(); }
        }
    }
    private static LocalDocumentPublisher.Failure unsupported() {
        return LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_UNSUPPORTED_REPOSITORY");
    }
    Optional<ObjectId> head() throws IOException { return Optional.ofNullable(repository.resolve(Constants.HEAD)); }
    String branch() throws IOException { return repository.exactRef(Constants.HEAD).getTarget().getName(); }
    DirCache lockIndex() throws IOException {
        // Repository-bound caches refresh smudged entries on write using a filter-capable
        // FileTreeIterator. Own the same index lock without attaching a live worktree.
        return DirCache.lock(repository.getIndexFile(), repository.getFS());
    }
    void requireCleanIndex(DirCache index, Optional<ObjectId> head) throws IOException {
        DirCache expected = tree(head);
        if (index.getEntryCount() != expected.getEntryCount()) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_DIRTY_INDEX"); }
        for (int i = 0; i < index.getEntryCount(); i++) {
            DirCacheEntry actual = index.getEntry(i);
            DirCacheEntry wanted = expected.getEntry(i);
            if (actual.getStage() != 0 || !Arrays.equals(actual.getRawPath(), wanted.getRawPath())
                    || !actual.getObjectId().equals(wanted.getObjectId()) || !actual.getFileMode().equals(wanted.getFileMode())) {
                throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_DIRTY_INDEX");
            }
        }
    }
    DirCache tree(Optional<ObjectId> head) throws IOException {
        DirCache cache = DirCache.newInCore();
        if (head.isPresent()) {
            try (RevWalk walk = new RevWalk(repository); ObjectReader reader = repository.newObjectReader()) {
                return DirCache.read(reader, walk.parseCommit(head.orElseThrow()).getTree());
            }
        }
        return cache;
    }
    Optional<byte[]> catalog(Optional<ObjectId> head) throws IOException {
        if (head.isEmpty()) { return Optional.empty(); }
        try (RevWalk walk = new RevWalk(repository);
                TreeWalk tree = TreeWalk.forPath(repository, "navigation-catalog.json", walk.parseCommit(head.orElseThrow()).getTree())) {
            if (Objects.isNull(tree)) { return Optional.empty(); }
            if (!(tree.getFileMode(0).equals(FileMode.REGULAR_FILE) || tree.getFileMode(0).equals(FileMode.EXECUTABLE_FILE))) {
                throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_CATALOG_EDITED");
            }
            return Optional.of(repository.open(tree.getObjectId(0)).getBytes(DocumentContract.MAX_CATALOG_BYTES));
        }
    }
    void requireCatalogUnedited(Optional<byte[]> expected) throws IOException {
        Path path = root.resolve("navigation-catalog.json");
        if (expected.isEmpty()) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_CATALOG_EDITED"); }
        } else if (!Arrays.equals(expected.orElseThrow(), snapshot("navigation-catalog.json", DocumentContract.MAX_CATALOG_BYTES))) {
            throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_CATALOG_EDITED");
        }
    }
    byte[] snapshot(String path, int maximum) throws IOException {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        GitDocumentLoader.rejectSymlinkPath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) { throw LocalDocumentPublisher.refusal("DOCUMENT_CAPACITY_EXCEEDED"); }
            return bytes;
        }
    }
    record Candidate(ObjectId revision, DirCache tree, boolean created) { }
    Candidate candidate(Optional<ObjectId> head, Map<String, byte[]> replacements) throws IOException {
        DirCache base = tree(head);
        // Decoded names are display-only: distinct Git paths can decode identically.
        TreeMap<byte[], DirCacheEntry> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < base.getEntryCount(); i++) {
            DirCacheEntry entry = base.getEntry(i);
            entries.put(entry.getRawPath(), entry);
        }
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            for (Map.Entry<String, byte[]> replacement : replacements.entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(replacement.getKey());
                byte[] path = entry.getRawPath();
                DirCacheEntry old = entries.get(path);
                if (Objects.nonNull(old) && !(old.getFileMode().equals(FileMode.REGULAR_FILE) || old.getFileMode().equals(FileMode.EXECUTABLE_FILE))) {
                    throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT");
                }
                entry.setFileMode(Objects.isNull(old) ? FileMode.REGULAR_FILE : old.getFileMode());
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, replacement.getValue()));
                entries.put(path, entry);
            }
            DirCache result = DirCache.newInCore();
            DirCacheBuilder builder = result.builder();
            for (DirCacheEntry entry : entries.values()) { builder.add(entry); }
            builder.finish();
            ObjectId treeId = result.writeTree(inserter);
            if (head.isPresent()) {
                try (RevWalk walk = new RevWalk(repository)) {
                    if (treeId.equals(walk.parseCommit(head.orElseThrow()).getTree().getId())) { return new Candidate(head.orElseThrow(), result, false); }
                }
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            head.ifPresent(commit::setParentId);
            PersonIdent identity = new PersonIdent(localConfig.getString("user", null, "name"), localConfig.getString("user", null, "email"));
            commit.setAuthor(identity); commit.setCommitter(identity); commit.setMessage("Update local document catalog\n");
            ObjectId revision = inserter.insert(commit);
            inserter.flush();
            return new Candidate(revision, result, true);
        }
    }
    void commit(Candidate candidate, Optional<ObjectId> expected, String branch, DirCache index) throws IOException {
        supported();
        if (!branch.equals(branch()) || !head().equals(expected)) { throw concurrent(); }
        RefUpdate update = repository.updateRef(branch);
        update.setExpectedOldObjectId(expected.orElse(ObjectId.zeroId()));
        update.setNewObjectId(candidate.revision());
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE) { throw concurrent(); }
        DirCacheBuilder builder = index.builder();
        for (int i = 0; i < candidate.tree().getEntryCount(); i++) {
            DirCacheEntry replacement = candidate.tree().getEntry(i);
            byte[] path = replacement.getRawPath();
            int position = index.findEntry(path, path.length);
            DirCacheEntry existing = position >= 0 ? index.getEntry(position) : null;
            // Retain the complete index entry, including extended flags and stat data.
            builder.add(Objects.nonNull(existing) && existing.getObjectId().equals(replacement.getObjectId())
                    && existing.getFileMode().equals(replacement.getFileMode()) ? existing : replacement);
        }
        builder.finish();
        index.write();
        if (!index.commit()) { throw new IOException("Index replacement failed"); }
    }
    static LocalDocumentPublisher.Failure concurrent() {
        return new LocalDocumentPublisher.Failure(LocalDocumentPublisher.FailureKind.BUSY, "LOCAL_DOCUMENT_CONCURRENT_CHANGE", LocalDocumentPublisher.Phase.COMMIT, Optional.empty());
    }
    Path catalogPath() { return root.resolve("navigation-catalog.json"); }
    @Override public void close() { repository.close(); }
}
