package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.config.DocumentStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import org.eclipse.jgit.revwalk.RevCommit;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Set;
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
    private static final Set<PosixFilePermission> PRIVATE_ACCESS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Path CAPTURED_NAME = Path.of("captured.md");
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
    Candidate candidate(Optional<ObjectId> head, Map<String, byte[]> replacements, Set<String> removals,
            String operationFingerprint, boolean firstPublicationIntent) throws IOException {
        DirCache base = tree(head);
        // Decoded names are display-only: distinct Git paths can decode identically.
        TreeMap<byte[], DirCacheEntry> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < base.getEntryCount(); i++) {
            DirCacheEntry entry = base.getEntry(i);
            entries.put(entry.getRawPath(), entry);
        }
        for (String path : removals) { entries.remove(path.getBytes(StandardCharsets.UTF_8)); }
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
            boolean unchanged = false;
            String previousMessage = null;
            if (head.isPresent()) {
                try (RevWalk walk = new RevWalk(repository)) {
                    RevCommit current = walk.parseCommit(head.orElseThrow());
                    unchanged = treeId.equals(current.getTree().getId());
                    if (unchanged && !firstPublicationIntent) { return new Candidate(head.orElseThrow(), result, false); }
                    if (unchanged) { previousMessage = current.getFullMessage(); }
                }
            }
            String message = operationMessage(operationFingerprint);
            if (unchanged && message.equals(previousMessage)) { return new Candidate(head.orElseThrow(), result, false); }
            // First publication of an unselected owner needs this request's intent, not a foreign/legacy HEAD identity.
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            head.ifPresent(commit::setParentId);
            PersonIdent identity = new PersonIdent(localConfig.getString("user", null, "name"), localConfig.getString("user", null, "email"));
            commit.setAuthor(identity); commit.setCommitter(identity); commit.setMessage(message);
            ObjectId revision = inserter.insert(commit);
            inserter.flush();
            return new Candidate(revision, result, true);
        }
    }
    private static String operationMessage(String fingerprint) {
        return "Update local document catalog\n\nLocal-Document-Operation: " + fingerprint + "\n";
    }
    void requireOperation(ObjectId revision, String fingerprint) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            if (!walk.parseCommit(revision).getFullMessage().equals(operationMessage(fingerprint))) {
                throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_OPERATION_MISMATCH");
            }
        }
    }
    void requireHead(Optional<ObjectId> expected, String branch) throws IOException {
        supported();
        if (!branch.equals(branch()) || !head().equals(expected)) { throw concurrent(); }
    }
    void commit(Candidate candidate, Optional<ObjectId> expected, String branch, DirCache index) throws IOException {
        requireHead(expected, branch);
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
    byte[] trackedBytes(Optional<ObjectId> head, String path) throws IOException {
        DirCacheEntry entry = tree(head).getEntry(path);
        if (Objects.isNull(entry) || !(entry.getFileMode().equals(FileMode.REGULAR_FILE)
                || entry.getFileMode().equals(FileMode.EXECUTABLE_FILE))) {
            throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT");
        }
        return repository.open(entry.getObjectId()).getBytes(DocumentContract.MAX_DOCUMENT_BYTES);
    }
    byte[] removedBytes(Optional<ObjectId> head, String path) throws IOException {
        if (head.isEmpty()) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_OPERATION_MISMATCH"); }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(head.orElseThrow());
            if (commit.getParentCount() != 1) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_OPERATION_MISMATCH"); }
            return trackedBytes(Optional.of(commit.getParent(0).getId()), path);
        }
    }
    record Removal(String path, byte[] expected, Optional<BasicFileAttributes> attributes) { }
    Removal removal(String path, byte[] expected) throws IOException {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) { throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        GitDocumentLoader.rejectSymlinkPath(file);
        Optional<BasicFileAttributes> attributes = Optional.empty();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes found = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!found.isRegularFile() || !Arrays.equals(expected, snapshot(path, DocumentContract.MAX_DOCUMENT_BYTES))) {
                throw LocalDocumentPublisher.refusal("LOCAL_DOCUMENT_DIRTY_FILE");
            }
            attributes = Optional.of(found);
        }
        return new Removal(path, expected, attributes);
    }
    void requireRemovalUnchanged(Removal removal) throws IOException {
        Removal fresh = removal(removal.path(), removal.expected());
        if (removal.attributes().isPresent() != fresh.attributes().isPresent()
                || removal.attributes().isPresent() && !Objects.equals(removal.attributes().orElseThrow().fileKey(),
                        fresh.attributes().orElseThrow().fileKey())) { throw concurrent(); }
    }
    enum CapturePhase { BEFORE_CAPTURE, AFTER_CAPTURE }
    @FunctionalInterface interface CaptureBoundary {
        void enter(CapturePhase phase, Path original, Path captured) throws IOException;
    }
    void delete(Removal removal) throws IOException { delete(removal, (phase, original, captured) -> { }); }
    void delete(Removal removal, CaptureBoundary boundary) throws IOException {
        requireRemovalUnchanged(removal);
        if (removal.attributes().isEmpty()) { return; }
        Path file = root.resolve(removal.path());
        Path parentPath = file.getParent();
        Path name = file.getFileName();
        try (SecureDirectoryStream<Path> parent = openDirectory(parentPath)) {
            requireParentCurrent(parent, parentPath);
            requireMatching(parent, name, removal);
            if (Objects.isNull(parent.getFileAttributeView(PosixFileAttributeView.class))) {
                throw new IOException("Private removal capture requires POSIX directory access");
            }
            Path quarantine;
            try {
                quarantine = Files.createTempDirectory(parentPath, ".document-removal-",
                        PosixFilePermissions.asFileAttribute(PRIVATE_ACCESS));
            } catch (UnsupportedOperationException exception) {
                throw new IOException("Private removal capture is unavailable", exception);
            }
            Path quarantineName = quarantine.getFileName();
            Path capturedName = CAPTURED_NAME;
            boolean captured = false;
            boolean deleted = false;
            try (SecureDirectoryStream<Path> privateDirectory = parent.newDirectoryStream(quarantineName, LinkOption.NOFOLLOW_LINKS)) {
                PosixFileAttributeView access = privateDirectory.getFileAttributeView(PosixFileAttributeView.class);
                if (Objects.isNull(access) || !access.readAttributes().permissions().equals(PRIVATE_ACCESS)) {
                    throw new IOException("Removal capture directory is not private");
                }
                requireParentCurrent(parent, parentPath);
                boundary.enter(CapturePhase.BEFORE_CAPTURE, file, quarantine.resolve(capturedName));
                parent.move(name, privateDirectory, capturedName);
                captured = true;
                boundary.enter(CapturePhase.AFTER_CAPTURE, file, quarantine.resolve(capturedName));
                // Validate the object actually captured, not the pathname observed before the atomic move.
                requireMatching(privateDirectory, capturedName, removal);
                requireParentCurrent(parent, parentPath);
                if (exists(parent, name)) { throw concurrent(); }
                // Only this owned private directory's validated object is unlinked. Unexpected objects stay captured.
                privateDirectory.deleteFile(capturedName);
                deleted = true;
            } finally {
                // Never restore over a concurrently created target or discard an unexpected captured object.
                if (!captured || deleted) { parent.deleteDirectory(quarantineName); }
            }
        }
    }
    private static boolean exists(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        try {
            directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            return true;
        } catch (NoSuchFileException exception) { return false; }
    }
    private static void requireMatching(SecureDirectoryStream<Path> directory, Path name, Removal removal) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes before = view.readAttributes();
        Object expectedKey = removal.attributes().orElseThrow().fileKey();
        if (Objects.isNull(expectedKey) || Objects.isNull(before.fileKey())) {
            throw new IOException("Removal requires stable filesystem object identity");
        }
        if (!before.isRegularFile() || !Objects.equals(expectedKey, before.fileKey())) { throw concurrent(); }
        try (InputStream input = Channels.newInputStream(directory.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            if (!Arrays.equals(removal.expected(), input.readNBytes(DocumentContract.MAX_DOCUMENT_BYTES + 1))) { throw concurrent(); }
        }
        BasicFileAttributes after = view.readAttributes();
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) { throw concurrent(); }
    }
    private static void requireParentCurrent(SecureDirectoryStream<Path> parent, Path path) throws IOException {
        GitDocumentLoader.rejectSymlinkPath(path);
        BasicFileAttributes bound = parent.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Objects.isNull(bound.fileKey()) || Objects.isNull(current.fileKey())) {
            throw new IOException("Removal requires stable directory identity");
        }
        if (!Objects.equals(bound.fileKey(), current.fileKey())) { throw concurrent(); }
    }
    private static SecureDirectoryStream<Path> openDirectory(Path path) throws IOException {
        DirectoryStream<Path> initial = Files.newDirectoryStream(path.getRoot());
        if (!(initial instanceof SecureDirectoryStream<Path> directory)) {
            initial.close();
            throw new IOException("Secure directory-relative removal is unavailable");
        }
        try {
            for (Path name : path) {
                SecureDirectoryStream<Path> next = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                try { directory.close(); }
                catch (IOException failure) {
                    try { next.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                    throw failure;
                }
                directory = next;
            }
            return directory;
        } catch (IOException | RuntimeException failure) {
            try { directory.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    static LocalDocumentPublisher.Failure concurrent() {
        return new LocalDocumentPublisher.Failure(LocalDocumentPublisher.FailureKind.BUSY, "LOCAL_DOCUMENT_CONCURRENT_CHANGE", LocalDocumentPublisher.Phase.COMMIT, Optional.empty());
    }
    Path catalogPath() { return root.resolve("navigation-catalog.json"); }
    @Override public void close() { repository.close(); }
}
