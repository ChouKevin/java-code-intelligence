package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.catalog.DocumentCatalog.CatalogView;
import com.java.semantic.document.catalog.DocumentCatalog.RepositorySnapshot;
import com.java.semantic.document.config.DocumentStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** One admission policy for complete reader and offline publication candidates. */
public final class DocumentSelectionLoader {
    private static final Set<String> FIELDS = Set.of("formatVersion", "documentRepositoryId", "repositories");
    private final DocumentStorage properties;
    private final GitDocumentLoader loader;

    public DocumentSelectionLoader(DocumentStorage properties, GitDocumentLoader loader) {
        this.properties = properties;
        this.loader = loader;
    }

    public CatalogView load(byte[] bytes, Map<String, RepositorySnapshot> reusable, DocumentDeadline deadline) {
        deadline.check();
        if (bytes.length > DocumentContract.MAX_PUBLICATION_BYTES) { throw capacity(); }
        JsonNode selection = GitDocumentLoader.parseObject(bytes, FIELDS);
        JsonNode version = selection.path("formatVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 2
                || !GitDocumentLoader.text(selection, "documentRepositoryId").equals(properties.repositoryId())) {
            throw invalid();
        }
        JsonNode repositories = selection.path("repositories");
        if (!repositories.isArray()) { throw invalid(); }
        if (repositories.size() > DocumentContract.MAX_REPOSITORIES) { throw capacity(); }
        TreeMap<String, String> requested = new TreeMap<>();
        for (JsonNode row : repositories) {
            GitDocumentLoader.fields(row, Set.of("repositoryId", "documentRevision"));
            DocumentContext context;
            try { context = new DocumentContext(properties.repositoryId(), GitDocumentLoader.text(row, "repositoryId"),
                    GitDocumentLoader.text(row, "documentRevision")); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            if (Objects.nonNull(requested.put(context.repositoryId(), context.documentRevision()))) { throw invalid(); }
        }
        TreeMap<String, RepositorySnapshot> snapshots = new TreeMap<>();
        TreeMap<String, Set<String>> missing = new TreeMap<>();
        long totalBytes = 0;
        int totalSections = 0;
        int totalDocuments = 0;
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            deadline.check();
            RepositorySnapshot snapshot = reusable.get(entry.getKey());
            if (Objects.nonNull(snapshot) && snapshot.context().documentRevision().equals(entry.getValue())
                    && snapshot.context().documentRepositoryId().equals(properties.repositoryId())
                    && snapshot.context().repositoryId().equals(entry.getKey())) {
                snapshots.put(entry.getKey(), snapshot);
                totalBytes += snapshot.markdownBytes();
                totalSections += snapshot.sectionCount();
                totalDocuments += snapshot.documents().size();
            } else { missing.computeIfAbsent(entry.getValue(), ignored -> new HashSet<>()).add(entry.getKey()); }
        }
        requireBudget(totalDocuments, totalBytes, totalSections);
        for (Map.Entry<String, Set<String>> group : missing.entrySet()) {
            Map<String, RepositorySnapshot> loaded = loader.load(group.getKey(), group.getValue(), deadline,
                    DocumentContract.MAX_DOCUMENTS - totalDocuments, DocumentContract.MAX_TOTAL_BYTES - totalBytes,
                    DocumentContract.MAX_TOTAL_SECTIONS - totalSections);
            for (Map.Entry<String, RepositorySnapshot> entry : loaded.entrySet()) {
                RepositorySnapshot snapshot = entry.getValue();
                snapshots.put(entry.getKey(), snapshot);
                totalBytes += snapshot.markdownBytes();
                totalSections += snapshot.sectionCount();
                totalDocuments += snapshot.documents().size();
            }
            requireBudget(totalDocuments, totalBytes, totalSections);
        }
        TreeMap<String, DocumentCatalog.Document> documents = new TreeMap<>();
        for (RepositorySnapshot snapshot : snapshots.values()) { documents.putAll(snapshot.documents()); }
        return new CatalogView(properties.repositoryId(), properties.collectionPurpose(), snapshots, documents,
                globalVersion(requested), PublicationStatus.READY, Optional.empty());
    }

    private static String globalVersion(Map<String, String> revisions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : revisions.entrySet()) {
                digest.update((entry.getKey() + "=" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("Required SHA-256 unavailable", exception); }
    }
    private static void requireBudget(int documents, long bytes, int sections) {
        if (documents > DocumentContract.MAX_DOCUMENTS || bytes > DocumentContract.MAX_TOTAL_BYTES
                || sections > DocumentContract.MAX_TOTAL_SECTIONS) { throw capacity(); }
    }
    private static DocumentFailure capacity() { return new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED); }
    private static DocumentFailure invalid() { return new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
}
