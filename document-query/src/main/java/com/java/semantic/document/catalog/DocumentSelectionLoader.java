package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.catalog.DocumentCatalog.CatalogView;
import com.java.semantic.document.catalog.DocumentCatalog.RevisionSnapshot;
import com.java.semantic.document.config.DocumentStorage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** One admission policy for complete reader and offline publication candidates. */
public final class DocumentSelectionLoader {
    private static final Set<String> FIELDS = Set.of("formatVersion", "documentRepositoryId", "activeRevision", "retainedRevisions");
    private final DocumentStorage properties;
    private final GitDocumentLoader loader;

    public DocumentSelectionLoader(DocumentStorage properties, GitDocumentLoader loader) {
        this.properties = properties;
        this.loader = loader;
    }

    public CatalogView load(byte[] bytes, Map<String, RevisionSnapshot> reusable, DocumentDeadline deadline) {
        deadline.check();
        if (bytes.length > DocumentContract.MAX_PUBLICATION_BYTES) { throw new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED); }
        JsonNode selection = GitDocumentLoader.parseObject(bytes, FIELDS);
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
            RevisionSnapshot snapshot = reusable.get(revision);
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
}
