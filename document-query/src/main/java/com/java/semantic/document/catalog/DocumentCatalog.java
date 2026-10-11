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
import com.java.semantic.document.config.DocumentStorage;
import com.java.semantic.model.source.SourceContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    public record RepositorySnapshot(DocumentContext context, NavigableMap<String, Document> documents,
            long markdownBytes, int sectionCount) {
        public RepositorySnapshot { documents = Collections.unmodifiableNavigableMap(new TreeMap<>(documents)); }
    }
    public static final class CatalogView {
        private final String documentRepositoryId;
        private final CollectionPurpose collectionPurpose;
        private final NavigableMap<String, RepositorySnapshot> repositories;
        private final NavigableMap<String, Document> documents;
        private final String globalVersion;
        private final PublicationStatus publicationStatus;
        private final Optional<Code> publicationErrorCode;

        public CatalogView(String documentRepositoryId, CollectionPurpose collectionPurpose,
                NavigableMap<String, RepositorySnapshot> repositories, NavigableMap<String, Document> documents,
                String globalVersion, PublicationStatus publicationStatus, Optional<Code> publicationErrorCode) {
            this.documentRepositoryId = documentRepositoryId;
            this.collectionPurpose = collectionPurpose;
            this.repositories = Collections.unmodifiableNavigableMap(new TreeMap<>(repositories));
            this.documents = Collections.unmodifiableNavigableMap(new TreeMap<>(documents));
            this.globalVersion = globalVersion;
            this.publicationStatus = publicationStatus;
            this.publicationErrorCode = publicationErrorCode;
        }
        private CatalogView(CatalogView admitted, Code code) {
            documentRepositoryId = admitted.documentRepositoryId;
            collectionPurpose = admitted.collectionPurpose;
            repositories = admitted.repositories;
            documents = admitted.documents;
            globalVersion = admitted.globalVersion;
            publicationStatus = PublicationStatus.UPDATE_REJECTED;
            publicationErrorCode = Optional.of(code);
        }
        public String documentRepositoryId() { return documentRepositoryId; }
        public CollectionPurpose collectionPurpose() { return collectionPurpose; }
        public NavigableMap<String, RepositorySnapshot> repositories() { return repositories; }
        public NavigableMap<String, Document> documents() { return documents; }
        public String globalVersion() { return globalVersion; }
        public PublicationStatus publicationStatus() { return publicationStatus; }
        public Optional<Code> publicationErrorCode() { return publicationErrorCode; }
        public RepositorySnapshot requireCurrent(DocumentContext context) {
            if (!documentRepositoryId.equals(context.documentRepositoryId())) {
                throw new DocumentFailure(Code.DOCUMENT_REPOSITORY_NOT_FOUND);
            }
            RepositorySnapshot snapshot = repositories.get(context.repositoryId());
            if (Objects.isNull(snapshot) || !snapshot.context().equals(context)) {
                throw new DocumentFailure(Code.DOCUMENT_REVISION_NOT_AVAILABLE);
            }
            return snapshot;
        }
        private CatalogView rejected(Code code) {
            return new CatalogView(this, code);
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
    private final DocumentStorage properties;
    private final DocumentSelectionLoader selectionLoader;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private CatalogView current;
    private byte[] acceptedSelection;

    public DocumentCatalog(DocumentStorage properties, GitDocumentLoader loader) {
        this.properties = properties;
        this.selectionLoader = new DocumentSelectionLoader(properties, loader);
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
                    CatalogView candidate = selectionLoader.load(fresh.bytes(),
                            Objects.isNull(current) ? Map.of() : current.repositories(), deadline);
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
