package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.config.DocumentStorage;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.model.source.SourceContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.lib.ObjectId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit offline operator. A successful result never verifies a running service. */
public final class LocalDocumentPublisher implements AutoCloseable {
    public record UpdateRequest(SourceContext primaryContext, List<SourceContext> relatedContexts, List<String> fileNames,
            Optional<String> reviewReference, List<String> withdrawnRevisions) {
        public UpdateRequest {
            Objects.requireNonNull(primaryContext); relatedContexts = List.copyOf(relatedContexts);
            fileNames = List.copyOf(fileNames); Objects.requireNonNull(reviewReference); withdrawnRevisions = List.copyOf(withdrawnRevisions);
        }
    }
    public record Result(String operation, String documentRepositoryId, Optional<String> candidateRevision,
            Optional<String> activeRevision, List<String> retainedRevisions, boolean commitCreated, boolean selectionChanged) {
        public Result { retainedRevisions = List.copyOf(retainedRevisions); }
    }
    public enum FailureKind { INVALID, BUSY, IO }
    public enum Phase { PREPARE, CANDIDATE, COMMIT, ACTIVATE, STATUS }
    public static final class Failure extends RuntimeException {
        private final FailureKind kind;
        private final String code;
        private final Phase phase;
        private final Optional<String> candidateRevision;
        public Failure(FailureKind kind, String code, Phase phase, Optional<String> candidateRevision) {
            super(code); this.kind = kind; this.code = code; this.phase = phase; this.candidateRevision = candidateRevision;
        }
        public FailureKind kind() { return kind; }
        public String code() { return code; }
        public Phase phase() { return phase; }
        public Optional<String> candidateRevision() { return candidateRevision; }
    }
    @FunctionalInterface interface Boundary { void enter(Phase phase) throws IOException; }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DocumentStorage storage;
    private final LocalDocumentGit git;
    private final GitDocumentLoader loader;
    private final DocumentSelectionLoader admission;
    private final Boundary boundary;
    private boolean closed;
    public LocalDocumentPublisher(DocumentStorage storage) { this(storage, phase -> { }); }
    LocalDocumentPublisher(DocumentStorage storage, Boundary boundary) {
        this.storage = Objects.requireNonNull(storage); this.boundary = boundary;
        LocalDocumentGit opened;
        try { opened = new LocalDocumentGit(storage); }
        catch (DocumentFailure failure) { throw refusal(failure.code().name()); }
        catch (IOException exception) { throw new Failure(FailureKind.IO, "LOCAL_DOCUMENT_IO_FAILURE", Phase.PREPARE, Optional.empty()); }
        git = opened;
        try { loader = new GitDocumentLoader(storage, new DocumentResultWriter(JSON)); }
        catch (DocumentFailure failure) { git.close(); throw new Failure(FailureKind.IO, failure.code().name(), Phase.PREPARE, Optional.empty()); }
        catch (RuntimeException exception) { git.close(); throw exception; }
        admission = new DocumentSelectionLoader(storage, loader);
    }
    static Failure refusal(String code) { return new Failure(FailureKind.INVALID, code, Phase.PREPARE, Optional.empty()); }
    private DocumentDeadline deadline() { return DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT); }
    private void requireOpen() { if (closed) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); } }
    private Optional<byte[]> observe() throws IOException {
        Path path = storage.publicationFile();
        GitDocumentLoader.rejectSymlinkPath(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return Optional.empty(); }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(DocumentContract.MAX_PUBLICATION_BYTES + 1);
            if (bytes.length > DocumentContract.MAX_PUBLICATION_BYTES) { throw refusal("DOCUMENT_CAPACITY_EXCEEDED"); }
            return Optional.of(bytes);
        }
    }
    private Optional<DocumentCatalog.CatalogView> admitted(Optional<byte[]> selection) {
        return selection.map(bytes -> admission.load(bytes, Map.of(), deadline()));
    }
    private record Selection(byte[] bytes, List<String> retained) { }
    private Selection selection(String revision, List<String> withdrawn, Optional<DocumentCatalog.CatalogView> old) {
        new DocumentContract.DocumentContext(storage.repositoryId(), revision);
        Set<String> withdrawals = new HashSet<>();
        for (String value : withdrawn) {
            new DocumentContract.DocumentContext(storage.repositoryId(), value);
            if (!withdrawals.add(value) || value.equals(revision)) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        }
        List<String> retained = new ArrayList<>();
        old.ifPresent(view -> retained.addAll(view.revisions().keySet().stream().sorted().toList()));
        if (!retained.containsAll(withdrawals)) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        retained.removeAll(withdrawals);
        if (!retained.contains(revision)) { retained.add(revision); }
        byte[] bytes = JSON.writeValueAsBytes(Map.of("formatVersion", 1, "documentRepositoryId", storage.repositoryId(),
                "activeRevision", revision, "retainedRevisions", retained));
        admission.load(bytes, Map.of(), deadline());
        return new Selection(bytes, List.copyOf(retained));
    }
    public Result update(UpdateRequest request) { return mutate(request, null, List.of()); }
    public Result activate(String revision, List<String> withdrawnRevisions) { return mutate(null, revision, withdrawnRevisions); }
    private Result mutate(UpdateRequest request, String revision, List<String> withdrawn) {
        requireOpen();
        Phase phase = Phase.PREPARE;
        Optional<String> candidate = Optional.empty();
        Path lock = storage.gitDirectory().resolve("document-local-writer.lock");
        boolean ownsLock = false;
        DirCache index = null;
        try {
            try { Files.createFile(lock); ownsLock = true; }
            catch (FileAlreadyExistsException exception) { throw new Failure(FailureKind.BUSY, "LOCAL_DOCUMENT_BUSY", phase, candidate); }
            git.supported();
            Optional<byte[]> observed = observe();
            Optional<DocumentCatalog.CatalogView> old = admitted(observed);
            Optional<ObjectId> head = git.head();
            String branch = git.branch();
            LocalDocumentGit.Candidate objects = null;
            byte[] catalog = null;
            Optional<byte[]> originalCatalog = Optional.empty();
            if (Objects.nonNull(request)) {
                index = git.lockIndex();
                git.requireCleanIndex(index, head);
                originalCatalog = git.catalog(head);
                git.requireCatalogUnedited(originalCatalog);
                phase = Phase.CANDIDATE;
                Map<String, byte[]> replacements = replacements(request, originalCatalog);
                catalog = replacements.get("navigation-catalog.json");
                objects = git.candidate(head, replacements);
                revision = objects.revision().name();
                candidate = Optional.of(revision);
                withdrawn = request.withdrawnRevisions();
            } else { phase = Phase.ACTIVATE; candidate = Optional.ofNullable(revision); }
            Selection next = selection(revision, withdrawn, old);
            boolean changed = old.isEmpty() || !old.orElseThrow().activeRevision().equals(revision)
                    || !old.orElseThrow().revisions().keySet().equals(new HashSet<>(next.retained()));
            if (Objects.nonNull(objects) && objects.created()) {
                phase = Phase.COMMIT;
                boundary.enter(phase);
                requireObservation(observed);
                git.requireCatalogUnedited(originalCatalog);
                git.commit(objects, head, branch, index);
                replace(git.catalogPath(), catalog);
            }
            phase = Phase.ACTIVATE;
            if (changed) {
                boundary.enter(phase);
                requireObservation(observed);
                replace(storage.publicationFile(), next.bytes());
            }
            return new Result(Objects.nonNull(request) ? "update" : "activate", storage.repositoryId(), candidate,
                    Optional.of(revision), next.retained(), Objects.nonNull(objects) && objects.created(), changed);
        } catch (Failure failure) { throw new Failure(failure.kind(), failure.code(), phase, candidate); }
        catch (DocumentFailure failure) { throw new Failure(failure.code() == DocumentFailure.Code.DOCUMENT_UNAVAILABLE ? FailureKind.IO : FailureKind.INVALID, failure.code().name(), phase, candidate); }
        catch (LockFailedException exception) { throw new Failure(FailureKind.BUSY, "LOCAL_DOCUMENT_BUSY", phase, candidate); }
        catch (IOException exception) { throw new Failure(FailureKind.IO, "LOCAL_DOCUMENT_IO_FAILURE", phase, candidate); }
        catch (IllegalArgumentException | NullPointerException exception) { throw new Failure(FailureKind.INVALID, "LOCAL_DOCUMENT_INVALID_ARGUMENT", phase, candidate); }
        finally {
            if (Objects.nonNull(index)) { index.unlock(); }
            if (ownsLock) {
                try { Files.delete(lock); }
                catch (IOException exception) { throw new Failure(FailureKind.IO, "LOCAL_DOCUMENT_IO_FAILURE", phase, candidate); }
            }
        }
    }
    private Map<String, byte[]> replacements(UpdateRequest request, Optional<byte[]> oldCatalog) throws IOException {
        if (request.fileNames().isEmpty() || request.fileNames().size() > DocumentContract.MAX_DOCUMENTS
                || request.relatedContexts().size() > DocumentContract.MAX_RELATED_CONTEXTS
                || request.reviewReference().filter(String::isBlank).isPresent()
                || storage.collectionPurpose() == DocumentContract.CollectionPurpose.APPROVED && request.reviewReference().isEmpty()) {
            throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT");
        }
        Set<String> contexts = new HashSet<>();
        contexts.add(request.primaryContext().repositoryId());
        for (SourceContext related : request.relatedContexts()) {
            if (!contexts.add(related.repositoryId())) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        }
        Map<String, Object> rows = new java.util.TreeMap<>();
        if (oldCatalog.isPresent()) {
            JsonNode catalog = GitDocumentLoader.parseObject(oldCatalog.orElseThrow(), Set.of("formatVersion", "documentRepositoryId", "documents"));
            GitDocumentLoader.requireVersion(catalog);
            if (!GitDocumentLoader.text(catalog, "documentRepositoryId").equals(storage.repositoryId()) || !catalog.path("documents").isArray()) {
                throw refusal("DOCUMENT_CATALOG_INVALID");
            }
            for (JsonNode row : catalog.path("documents")) {
                String path = GitDocumentLoader.text(row, "path");
                if (Objects.nonNull(rows.put(path, row))) { throw refusal("DOCUMENT_CATALOG_INVALID"); }
            }
        }
        boolean metadataChanged = oldCatalog.isEmpty();
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        for (String name : request.fileNames()) {
            if (name.isEmpty() || !name.endsWith(".md") || name.contains("/") || name.contains("\\")
                    || name.codePoints().anyMatch(Character::isISOControl)) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
            String path = "repositories/" + request.primaryContext().repositoryId() + "/" + name;
            if (replacements.containsKey(path)) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
            byte[] bytes = git.snapshot(path, DocumentContract.MAX_DOCUMENT_BYTES);
            replacements.put(path, bytes);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path); row.put("contentDigest", digest(bytes)); row.put("primaryContext", request.primaryContext());
            row.put("relatedContexts", request.relatedContexts()); row.put("reviewStatus", request.reviewReference().isPresent() ? "APPROVED" : "DRAFT");
            request.reviewReference().ifPresent(value -> row.put("reviewReference", value));
            JsonNode encoded = JSON.readTree(JSON.writeValueAsBytes(row));
            if (!encoded.equals(rows.get(path))) { metadataChanged = true; }
            rows.put(path, encoded);
        }
        byte[] catalog = metadataChanged
                ? JSON.writeValueAsBytes(Map.of("formatVersion", 1, "documentRepositoryId", storage.repositoryId(), "documents", rows.values()))
                : oldCatalog.orElseThrow();
        if (catalog.length > DocumentContract.MAX_CATALOG_BYTES) { throw refusal("DOCUMENT_CAPACITY_EXCEEDED"); }
        replacements.put("navigation-catalog.json", catalog);
        return replacements;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private void requireObservation(Optional<byte[]> expected) throws IOException {
        Optional<byte[]> actual = observe();
        if (actual.isPresent() != expected.isPresent() || actual.isPresent() && !Arrays.equals(actual.orElseThrow(), expected.orElseThrow())) {
            throw LocalDocumentGit.concurrent();
        }
    }
    private static void replace(Path destination, byte[] bytes) throws IOException {
        GitDocumentLoader.rejectSymlinkPath(destination);
        Path temporary = Files.createTempFile(destination.getParent(), ".document-local-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                preserveAccess(destination, temporary);
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void preserveAccess(Path destination, Path temporary) throws IOException {
        BasicFileAttributes existing;
        try { existing = Files.readAttributes(destination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException exception) { return; } // First publication keeps restrictive temporary-file access.
        if (existing.isSymbolicLink()) { throw new IOException("Cannot preserve symbolic-link access"); }
        try {
            FileOwnerAttributeView ownerView = Files.getFileAttributeView(destination, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributeView posixView = Files.getFileAttributeView(destination, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            AclFileAttributeView aclView = Files.getFileAttributeView(destination, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            UserPrincipal owner = Objects.nonNull(ownerView) ? ownerView.getOwner() : null;
            PosixFileAttributes posix = Objects.nonNull(posixView) ? posixView.readAttributes() : null;
            List<AclEntry> acl = Objects.nonNull(aclView) ? aclView.getAcl() : null;
            FileOwnerAttributeView temporaryOwner = Files.getFileAttributeView(temporary, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributeView temporaryPosix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            AclFileAttributeView temporaryAcl = Files.getFileAttributeView(temporary, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            // Owner identity is part of access policy: copying mode alone could grant owner rights to another UID.
            if (Objects.nonNull(owner)) {
                if (Objects.isNull(temporaryOwner)) { throw new IOException("Cannot preserve file owner"); }
                if (!owner.equals(temporaryOwner.getOwner())) { temporaryOwner.setOwner(owner); }
            }
            if (Objects.nonNull(posix)) {
                if (Objects.isNull(temporaryPosix)) { throw new IOException("Cannot preserve POSIX access"); }
                if (!posix.group().equals(temporaryPosix.readAttributes().group())) { temporaryPosix.setGroup(posix.group()); }
                temporaryPosix.setPermissions(posix.permissions());
            }
            if (Objects.nonNull(acl)) {
                if (Objects.isNull(temporaryAcl)) { throw new IOException("Cannot preserve ACL access"); }
                temporaryAcl.setAcl(acl);
            }
            // ACL writes can also change POSIX attributes; require the complete policy to survive before rename.
            if (Objects.nonNull(owner) && !owner.equals(temporaryOwner.getOwner())) { throw new IOException("File owner was not preserved"); }
            if (Objects.nonNull(posix)) {
                PosixFileAttributes actual = temporaryPosix.readAttributes();
                if (!posix.owner().equals(actual.owner()) || !posix.group().equals(actual.group())
                        || !posix.permissions().equals(actual.permissions())) { throw new IOException("POSIX access was not preserved"); }
            }
            if (Objects.nonNull(acl) && !acl.equals(temporaryAcl.getAcl())) { throw new IOException("ACL access was not preserved"); }
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Cannot preserve publication access attributes", exception);
        }
    }
    public Result status() {
        requireOpen();
        try {
            git.supported();
            Optional<byte[]> observed = observe();
            Optional<DocumentCatalog.CatalogView> view = admitted(observed);
            requireObservation(observed);
            return new Result("status", storage.repositoryId(), git.head().map(ObjectId::name), view.map(DocumentCatalog.CatalogView::activeRevision),
                    view.map(value -> value.revisions().keySet().stream().sorted().toList()).orElse(List.of()), false, false);
        } catch (Failure failure) { throw new Failure(failure.kind(), failure.code(), Phase.STATUS, Optional.empty()); }
        catch (DocumentFailure failure) { throw new Failure(failure.code() == DocumentFailure.Code.DOCUMENT_UNAVAILABLE ? FailureKind.IO : FailureKind.INVALID, failure.code().name(), Phase.STATUS, Optional.empty()); }
        catch (IOException exception) { throw new Failure(FailureKind.IO, "LOCAL_DOCUMENT_IO_FAILURE", Phase.STATUS, Optional.empty()); }
    }
    @Override public void close() { if (!closed) { closed = true; loader.close(); git.close(); } }
}
