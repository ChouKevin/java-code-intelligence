package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.config.DocumentStorage;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.repository.RepositoryId;
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
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.LockFailedException;
import org.eclipse.jgit.lib.ObjectId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit offline operator. A successful result never verifies a running service. */
public final class LocalDocumentPublisher implements AutoCloseable {
    public record UpdateRequest(SourceContext primaryContext, List<SourceContext> relatedContexts, List<String> fileNames,
            Optional<String> reviewReference) {
        public UpdateRequest {
            Objects.requireNonNull(primaryContext); relatedContexts = List.copyOf(relatedContexts);
            fileNames = List.copyOf(fileNames); Objects.requireNonNull(reviewReference);
        }
    }
    public record RemoveRequest(String repositoryId, List<String> fileNames, Optional<String> reviewReference) {
        public RemoveRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            fileNames = List.copyOf(fileNames); Objects.requireNonNull(reviewReference);
        }
    }
    public record Result(String operation, String documentRepositoryId, Optional<String> repositoryId,
            Optional<String> candidateRevision, Map<String, String> activeRevisions, boolean commitCreated, boolean selectionChanged) {
        public Result { activeRevisions = Map.copyOf(activeRevisions); }
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
    private record Selection(byte[] bytes, Map<String, String> revisions) { }
    private static Map<String, String> active(Optional<DocumentCatalog.CatalogView> old) {
        Map<String, String> revisions = new java.util.TreeMap<>();
        old.ifPresent(view -> view.repositories().forEach((owner, snapshot) ->
                revisions.put(owner, snapshot.context().documentRevision())));
        return revisions;
    }
    private Selection selection(String owner, String revision, byte[] catalog, Optional<DocumentCatalog.CatalogView> old,
            Set<String> namedPaths) {
        Map<String, String> revisions = active(old);
        Map<String, DocumentCatalog.RepositorySnapshot> reusable = new java.util.TreeMap<>();
        old.ifPresent(view -> reusable.putAll(view.repositories()));
        boolean present = false;
        DocumentCatalog.RepositorySnapshot previous = old.map(view -> view.repositories().get(owner)).orElse(null);
        Map<String, DocumentCatalog.Document> previousDocuments = Objects.isNull(previous) ? Map.of() : previous.documents();
        Map<String, DocumentCatalog.Document> candidateDocuments = Map.of();
        for (JsonNode row : JSON.readTree(catalog).path("documents")) {
            if (GitDocumentLoader.text(row.path("primaryContext"), "repositoryId").equals(owner)) { present = true; break; }
        }
        if (present) {
            DocumentCatalog.RepositorySnapshot candidate = loader.load(revision, Set.of(owner), deadline(),
                    DocumentContract.MAX_DOCUMENTS, DocumentContract.MAX_TOTAL_BYTES, DocumentContract.MAX_TOTAL_SECTIONS).get(owner);
            candidateDocuments = candidate.documents();
            if (Objects.isNull(previous) || !previous.documents().equals(candidate.documents())) {
                revisions.put(owner, revision);
                reusable.put(owner, candidate);
            }
        } else { revisions.remove(owner); }
        for (Map.Entry<String, DocumentCatalog.Document> entry : previousDocuments.entrySet()) {
            if (!Objects.equals(entry.getValue(), candidateDocuments.get(entry.getKey())) && !namedPaths.contains(entry.getKey())) {
                throw refusal("LOCAL_DOCUMENT_OPERATION_MISMATCH");
            }
        }
        for (String path : candidateDocuments.keySet()) {
            if (!previousDocuments.containsKey(path) && !namedPaths.contains(path)) { throw refusal("LOCAL_DOCUMENT_OPERATION_MISMATCH"); }
        }
        List<Map<String, String>> entries = new ArrayList<>();
        revisions.forEach((id, sha) -> entries.add(Map.of("repositoryId", id, "documentRevision", sha)));
        byte[] bytes = JSON.writeValueAsBytes(Map.of("formatVersion", 2, "documentRepositoryId", storage.repositoryId(), "repositories", entries));
        admission.load(bytes, reusable, deadline());
        return new Selection(bytes, revisions);
    }
    private static String operationFingerprint(String owner, UpdateRequest update, RemoveRequest remove,
            Map<String, byte[]> replacements, List<LocalDocumentGit.Removal> deletions) {
        Map<String, String> content = new TreeMap<>();
        if (Objects.nonNull(update)) {
            for (String name : update.fileNames()) {
                String path = "repositories/" + owner + "/" + name;
                content.put(path, digest(replacements.get(path)));
            }
        } else {
            for (LocalDocumentGit.Removal deletion : deletions) { content.put(deletion.path(), digest(deletion.expected())); }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("operation", Objects.nonNull(update) ? "update" : "remove");
        input.put("repositoryId", owner);
        input.put("documents", content);
        if (Objects.nonNull(update)) {
            input.put("primaryContext", update.primaryContext());
            input.put("relatedContexts", update.relatedContexts());
            update.reviewReference().ifPresent(value -> input.put("reviewReference", value));
        } else { remove.reviewReference().ifPresent(value -> input.put("reviewReference", value)); }
        return digest(JSON.writeValueAsBytes(input));
    }
    public Result update(UpdateRequest request) { return mutate(Objects.requireNonNull(request), null); }
    public Result remove(RemoveRequest request) { return mutate(null, Objects.requireNonNull(request)); }
    private Result mutate(UpdateRequest request, RemoveRequest remove) {
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
            index = git.lockIndex();
            git.requireCleanIndex(index, head);
            Optional<byte[]> originalCatalog = git.catalog(head);
            git.requireCatalogUnedited(originalCatalog);
            phase = Phase.CANDIDATE;
            String owner = Objects.nonNull(request) ? request.primaryContext().repositoryId() : remove.repositoryId();
            List<LocalDocumentGit.Removal> deletions = new ArrayList<>();
            Map<String, byte[]> replacements = Objects.nonNull(request) ? replacements(request, originalCatalog)
                    : removals(remove, originalCatalog, head, old, deletions);
            byte[] catalog = replacements.get("navigation-catalog.json");
            Set<String> removedPaths = new HashSet<>();
            for (LocalDocumentGit.Removal deletion : deletions) { removedPaths.add(deletion.path()); }
            String fingerprint = operationFingerprint(owner, request, remove, replacements, deletions);
            boolean firstPublicationIntent = Objects.nonNull(request)
                    && old.map(view -> !view.repositories().containsKey(owner)).orElse(true);
            LocalDocumentGit.Candidate objects = git.candidate(head, replacements, removedPaths, fingerprint, firstPublicationIntent);
            String revision = objects.revision().name();
            candidate = Optional.of(revision);
            Set<String> namedPaths = new HashSet<>();
            for (String name : Objects.nonNull(request) ? request.fileNames() : remove.fileNames()) {
                namedPaths.add("repositories/" + owner + "/" + name);
            }
            Selection next = selection(owner, revision, catalog, old, namedPaths);
            String previousRevision = old.map(view -> view.repositories().get(owner))
                    .map(snapshot -> snapshot.context().documentRevision()).orElse(null);
            boolean ownerChanged = !Objects.equals(previousRevision, next.revisions().get(owner));
            if (!objects.created() && ownerChanged) {
                git.requireOperation(objects.revision(), fingerprint);
            }
            boolean changed = ownerChanged || observed.isEmpty();
            if (objects.created()) {
                phase = Phase.COMMIT;
                boundary.enter(phase);
                requireObservation(observed);
                git.requireCatalogUnedited(originalCatalog);
                for (LocalDocumentGit.Removal deletion : deletions) { git.requireRemovalUnchanged(deletion); }
                git.commit(objects, head, branch, index);
                replace(git.catalogPath(), catalog);
            }
            git.requireHead(Optional.of(objects.revision()), branch);
            requireObservation(observed);
            git.requireCatalogUnedited(Optional.of(catalog));
            for (LocalDocumentGit.Removal deletion : deletions) { git.delete(deletion); }
            phase = Phase.ACTIVATE;
            if (changed) {
                boundary.enter(phase);
                requireObservation(observed);
                git.requireHead(Optional.of(objects.revision()), branch);
                git.requireCatalogUnedited(Optional.of(catalog));
                replace(storage.publicationFile(), next.bytes());
            }
            return new Result(Objects.nonNull(request) ? "update" : "remove", storage.repositoryId(), Optional.of(owner), candidate,
                    next.revisions(), objects.created(), changed);
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
            validateName(name);
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
    private Map<String, byte[]> removals(RemoveRequest request, Optional<byte[]> catalogBytes, Optional<ObjectId> head,
            Optional<DocumentCatalog.CatalogView> old, List<LocalDocumentGit.Removal> deletions) throws IOException {
        if (request.fileNames().isEmpty() || request.fileNames().size() > DocumentContract.MAX_DOCUMENTS
                || request.reviewReference().filter(String::isBlank).isPresent()
                || storage.collectionPurpose() == DocumentContract.CollectionPurpose.APPROVED && request.reviewReference().isEmpty()
                || catalogBytes.isEmpty()) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
        JsonNode catalog = GitDocumentLoader.parseObject(catalogBytes.orElseThrow(),
                Set.of("formatVersion", "documentRepositoryId", "documents"));
        GitDocumentLoader.requireVersion(catalog);
        if (!GitDocumentLoader.text(catalog, "documentRepositoryId").equals(storage.repositoryId())
                || !catalog.path("documents").isArray()) { throw refusal("DOCUMENT_CATALOG_INVALID"); }
        Map<String, JsonNode> rows = new java.util.TreeMap<>();
        for (JsonNode row : catalog.path("documents")) {
            if (Objects.nonNull(rows.put(GitDocumentLoader.text(row, "path"), row))) { throw refusal("DOCUMENT_CATALOG_INVALID"); }
        }
        Set<String> named = new HashSet<>();
        boolean changed = false;
        for (String name : request.fileNames()) {
            validateName(name);
            String path = "repositories/" + request.repositoryId() + "/" + name;
            if (!named.add(path)) { throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT"); }
            JsonNode row = rows.get(path);
            byte[] expected;
            if (Objects.nonNull(row)) {
                if (!GitDocumentLoader.text(row.path("primaryContext"), "repositoryId").equals(request.repositoryId())) {
                    throw refusal("DOCUMENT_CATALOG_INVALID");
                }
                expected = git.trackedBytes(head, path);
                rows.remove(path);
                changed = true;
            } else {
                DocumentCatalog.RepositorySnapshot previous = old.map(view -> view.repositories().get(request.repositoryId())).orElse(null);
                DocumentCatalog.Document published = Objects.isNull(previous) ? null : previous.documents().get(path);
                if (Objects.isNull(published) || Objects.nonNull(git.tree(head).getEntry(path))) {
                    throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT");
                }
                expected = git.removedBytes(head, path);
            }
            deletions.add(git.removal(path, expected));
        }
        byte[] next = changed ? JSON.writeValueAsBytes(Map.of("formatVersion", 1, "documentRepositoryId", storage.repositoryId(),
                "documents", rows.values())) : catalogBytes.orElseThrow();
        return Map.of("navigation-catalog.json", next);
    }
    private static void validateName(String name) {
        if (name.isEmpty() || !name.endsWith(".md") || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(code -> Character.isISOControl(code)
                        || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) {
            throw refusal("LOCAL_DOCUMENT_INVALID_ARGUMENT");
        }
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
            return new Result("status", storage.repositoryId(), Optional.empty(), git.head().map(ObjectId::name),
                    active(view), false, false);
        } catch (Failure failure) { throw new Failure(failure.kind(), failure.code(), Phase.STATUS, Optional.empty()); }
        catch (DocumentFailure failure) { throw new Failure(failure.code() == DocumentFailure.Code.DOCUMENT_UNAVAILABLE ? FailureKind.IO : FailureKind.INVALID, failure.code().name(), Phase.STATUS, Optional.empty()); }
        catch (IOException exception) { throw new Failure(FailureKind.IO, "LOCAL_DOCUMENT_IO_FAILURE", Phase.STATUS, Optional.empty()); }
    }
    @Override public void close() { if (!closed) { closed = true; loader.close(); git.close(); } }
}
