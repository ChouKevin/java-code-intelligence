package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentContract.ReadResult;
import com.java.semantic.document.application.DocumentContract.ReviewStatus;
import com.java.semantic.document.application.DocumentContract.Section;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.config.DocumentQueryProperties;
import com.java.semantic.model.source.SourceContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import tools.jackson.databind.JsonNode;

/** Complete immutable selection replacement; at most current plus one candidate full state. */
public final class DocumentCatalog {
    public record Document(String path, String blobId, String contentDigest, int byteLength, String content,
            List<MarkdownSections.Span> sections, SourceContext primaryContext, List<SourceContext> relatedContexts,
            ReviewStatus reviewStatus, Optional<String> reviewReference) {
        public Document { sections = List.copyOf(sections); relatedContexts = List.copyOf(relatedContexts); }
        public ReadResult readResult(DocumentContext context, Section section, CollectionPurpose purpose,
                PublicationStatus status, Optional<Code> error) {
            return new ReadResult(context, path, blobId, contentDigest, byteLength, section, primaryContext,
                    relatedContexts, reviewStatus, reviewReference, purpose, DocumentContract.Trust.NOT_VERIFIED,
                    status, error, DocumentContract.Scope.FULL_DOCUMENT, 1, sections.getLast().endLine(), content);
        }
    }
    public record RevisionSnapshot(DocumentContext context, NavigableMap<String, Document> documents,
            long markdownBytes, int sectionCount) {
        public RevisionSnapshot { documents = Collections.unmodifiableNavigableMap(new TreeMap<>(documents)); }
    }
    public record CatalogView(String documentRepositoryId, String activeRevision, CollectionPurpose collectionPurpose,
            Map<String, RevisionSnapshot> revisions, PublicationStatus publicationStatus, Optional<Code> publicationErrorCode) {
        public CatalogView { revisions = Map.copyOf(revisions); }
        public DocumentContext context(String revision) {
            RevisionSnapshot snapshot = revisions.get(revision);
            if (Objects.isNull(snapshot)) { throw new DocumentFailure(Code.DOCUMENT_REVISION_NOT_AVAILABLE); }
            return snapshot.context();
        }
        private CatalogView rejected(Code code) {
            return new CatalogView(documentRepositoryId, activeRevision, collectionPurpose, revisions,
                    PublicationStatus.UPDATE_REJECTED, Optional.of(code));
        }
    }
    public static final class ReadLease implements AutoCloseable {
        private CatalogView view;
        private final Lock lock;
        private ReadLease(CatalogView view, Lock lock) { this.view = view; this.lock = lock; }
        /** Borrowed only until close; materialize a bounded detached result while holding this lease. */
        public CatalogView view() { return Objects.requireNonNull(view, "Document read lease is closed"); }
        @Override public void close() {
            if (Objects.nonNull(view)) { view = null; lock.unlock(); }
        }
    }
    private record Observation(byte[] bytes, Optional<Code> failure) { }
    private static final Set<String> SELECTION_FIELDS = Set.of("formatVersion", "documentRepositoryId", "activeRevision", "retainedRevisions");
    private final DocumentQueryProperties properties;
    private final GitDocumentLoader loader;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private CatalogView current;
    private byte[] acceptedSelection;

    public DocumentCatalog(DocumentQueryProperties properties, GitDocumentLoader loader) {
        this.properties = properties;
        this.loader = loader;
        try (ReadLease initial = open(DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT))) {
            initial.view();
        }
    }

    public ReadLease open(DocumentDeadline deadline) {
        Observation observation = observe(deadline);
        acquire(lock.readLock(), deadline);
        boolean keepRead = false;
        try {
            deadline.check();
            if (observation.failure().isPresent()) {
                CatalogView view = rejected(observation.failure().orElseThrow());
                keepRead = true;
                return new ReadLease(view, lock.readLock());
            }
            if (Objects.nonNull(current) && Arrays.equals(acceptedSelection, observation.bytes())) {
                keepRead = true;
                return new ReadLease(current, lock.readLock());
            }
        } finally {
            if (!keepRead) { lock.readLock().unlock(); }
        }
        // Never upgrade while holding a read lease. Fair writers drain all prior readers before allocation.
        acquire(lock.writeLock(), deadline);
        try {
            CatalogView selected;
            try {
                Observation fresh = observe(deadline);
                if (fresh.failure().isPresent()) { throw new DocumentFailure(fresh.failure().orElseThrow()); }
                if (Objects.nonNull(current) && Arrays.equals(acceptedSelection, fresh.bytes())) {
                    selected = current;
                } else {
                    CatalogView candidate = loadSelection(fresh.bytes(), deadline);
                    Observation finalSelection = observe(deadline);
                    if (finalSelection.failure().isPresent() || !Arrays.equals(fresh.bytes(), finalSelection.bytes())) {
                        throw new DocumentFailure(Code.DOCUMENT_UNAVAILABLE);
                    }
                    deadline.check();
                    current = candidate;
                    acceptedSelection = fresh.bytes();
                    selected = current;
                }
            } catch (DocumentFailure failure) {
                if (failure.code() == Code.DOCUMENT_TIMEOUT) { throw failure; }
                selected = rejected(failure.code());
            }
            deadline.check();
            lock.readLock().lock(); // Downgrade under the write lock; no intervening activation is possible.
            return new ReadLease(selected, lock.readLock());
        } finally { lock.writeLock().unlock(); }
    }

    private CatalogView rejected(Code code) {
        Code publicationCode = code == Code.DOCUMENT_RESULT_TOO_LARGE ? Code.DOCUMENT_CAPACITY_EXCEEDED : code;
        if (Objects.isNull(current)) { throw new DocumentFailure(publicationCode); }
        return current.rejected(publicationCode);
    }

    private Observation observe(DocumentDeadline deadline) {
        deadline.check();
        try {
            GitDocumentLoader.rejectSymlinkPath(properties.publicationFile());
            if (!Files.isRegularFile(properties.publicationFile(), LinkOption.NOFOLLOW_LINKS)) {
                throw new DocumentFailure(Code.DOCUMENT_UNAVAILABLE);
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(properties.publicationFile(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(DocumentContract.MAX_PUBLICATION_BYTES + 1);
            }
            if (bytes.length > DocumentContract.MAX_PUBLICATION_BYTES) { throw new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED); }
            deadline.check();
            return new Observation(bytes, Optional.empty());
        } catch (DocumentFailure failure) {
            if (failure.code() == Code.DOCUMENT_TIMEOUT) { throw failure; }
            return new Observation(new byte[0], Optional.of(failure.code()));
        } catch (IOException exception) {
            return new Observation(new byte[0], Optional.of(Code.DOCUMENT_UNAVAILABLE));
        }
    }

    private CatalogView loadSelection(byte[] bytes, DocumentDeadline deadline) {
        JsonNode selection = GitDocumentLoader.parseObject(bytes, SELECTION_FIELDS);
        GitDocumentLoader.requireVersion(selection);
        if (!GitDocumentLoader.text(selection, "documentRepositoryId").equals(properties.repositoryId())) {
            throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID);
        }
        String active = GitDocumentLoader.text(selection, "activeRevision");
        JsonNode revisions = selection.path("retainedRevisions");
        if (!revisions.isArray() || revisions.isEmpty()) { throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
        if (revisions.size() > DocumentContract.MAX_REVISIONS) { throw new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED); }
        Set<String> requested = new HashSet<>();
        for (JsonNode revision : revisions) {
            if (!revision.isString()) { throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
            String value = revision.stringValue();
            try { new DocumentContext(properties.repositoryId(), value); }
            catch (IllegalArgumentException exception) { throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
            if (!requested.add(value)) { throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
        }
        if (!requested.contains(active)) { throw new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
        Map<String, RevisionSnapshot> snapshots = new LinkedHashMap<>();
        long totalBytes = 0;
        int totalSections = 0;
        for (String revision : requested) {
            deadline.check();
            RevisionSnapshot snapshot = Objects.isNull(current) ? null : current.revisions().get(revision);
            if (Objects.isNull(snapshot)) {
                snapshot = loader.load(revision, deadline, DocumentContract.MAX_TOTAL_BYTES - totalBytes,
                        DocumentContract.MAX_TOTAL_SECTIONS - totalSections);
            }
            if (snapshot.markdownBytes() > DocumentContract.MAX_TOTAL_BYTES - totalBytes
                    || snapshot.sectionCount() > DocumentContract.MAX_TOTAL_SECTIONS - totalSections) {
                throw new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED);
            }
            snapshots.put(revision, snapshot);
            totalBytes += snapshot.markdownBytes();
            totalSections += snapshot.sectionCount();
        }
        return new CatalogView(properties.repositoryId(), active, properties.collectionPurpose(), snapshots,
                PublicationStatus.READY, Optional.empty());
    }

    private static void acquire(Lock lock, DocumentDeadline deadline) {
        deadline.check();
        try {
            if (!lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw new DocumentFailure(Code.DOCUMENT_TIMEOUT);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DocumentFailure(Code.DOCUMENT_TIMEOUT);
        }
    }
}
