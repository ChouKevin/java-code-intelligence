package com.java.semantic.document.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class DocumentContract {
    private DocumentContract() { }
    public static final int MAX_PUBLICATION_BYTES = 16 * 1024;
    public static final int MAX_REVISIONS = 8;
    public static final int MAX_CATALOG_BYTES = 256 * 1024;
    public static final int MAX_DOCUMENTS = 128;
    public static final int MAX_DOCUMENT_BYTES = 64 * 1024;
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    public static final int MAX_SECTIONS = 256;
    public static final int MAX_TOTAL_SECTIONS = 8192;
    public static final int MAX_PATH_BYTES = 512;
    public static final int MAX_TITLE_CODE_POINTS = 160;
    public static final int MAX_SNIPPET_CODE_POINTS = 240;
    public static final int MAX_QUERY_CODE_POINTS = 128;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    public static final int MAX_REQUEST_BYTES = 16 * 1024;
    public static final int MAX_CURSOR_BYTES = 4 * 1024;
    public static final int MAX_RESULT_BYTES = 512 * 1024;
    public static final int MAX_RELATED_CONTEXTS = 32;
    public static final int MAX_ACTIVE_OPERATIONS = 2;
    public static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    public enum CollectionPurpose { TEST_ONLY, APPROVED }
    public enum ReviewStatus { DRAFT, APPROVED }
    public enum Trust { NOT_VERIFIED }
    public enum PublicationStatus { READY, UPDATE_REJECTED }
    public enum Scope { FULL_DOCUMENT }

    public record DocumentContext(String documentRepositoryId, String documentRevision) {
        public DocumentContext {
            documentRepositoryId = new RepositoryId(documentRepositoryId).value();
            documentRevision = RepositoryRevision.ofSha(documentRevision).value();
        }
    }
    public record Section(int startLine, String title, boolean titleTruncated) { }
    public record SearchRequest(String query, int limit, Optional<String> cursor) { }
    public record ReadRequest(DocumentContext documentContext, String path, int sectionStartLine) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record Page(int returned, boolean hasMore, Optional<String> nextCursor) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record SearchHit(DocumentContext documentContext, String path, String contentDigest, Section section,
            String snippet, boolean snippetTruncated, SourceContext primaryContext, List<SourceContext> relatedContexts,
            ReviewStatus reviewStatus, Optional<String> reviewReference, CollectionPurpose collectionPurpose, Trust trust) {
        public SearchHit { relatedContexts = List.copyOf(relatedContexts); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record SearchResult(DocumentContext documentContext, CollectionPurpose collectionPurpose, Trust trust,
            PublicationStatus publicationStatus, Optional<DocumentFailure.Code> publicationErrorCode,
            List<SearchHit> items, Page page) {
        public SearchResult { items = List.copyOf(items); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ReadResult(DocumentContext documentContext, String path, String blobId, String contentDigest,
            int byteLength, Section section, SourceContext primaryContext, List<SourceContext> relatedContexts,
            ReviewStatus reviewStatus, Optional<String> reviewReference, CollectionPurpose collectionPurpose, Trust trust,
            PublicationStatus publicationStatus, Optional<DocumentFailure.Code> publicationErrorCode,
            Scope scope, int startLine, int endLine, String content) {
        public ReadResult { relatedContexts = List.copyOf(relatedContexts); }
    }
    public record Clipped(String text, boolean truncated) { }
    public static Clipped clip(String text, int maximumCodePoints) {
        int count = text.codePointCount(0, text.length());
        return count <= maximumCodePoints ? new Clipped(text, false)
                : new Clipped(text.substring(0, text.offsetByCodePoints(0, maximumCodePoints)), true);
    }
}
