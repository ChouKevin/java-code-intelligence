package com.java.semantic.document.application;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.Page;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentContract.ReadResult;
import com.java.semantic.document.application.DocumentContract.SearchHit;
import com.java.semantic.document.application.DocumentContract.SearchRequest;
import com.java.semantic.document.application.DocumentContract.SearchResult;
import com.java.semantic.document.application.DocumentContract.Trust;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.catalog.DocumentCatalog.CatalogView;
import com.java.semantic.document.catalog.DocumentCatalog.ReadLease;
import com.java.semantic.document.catalog.DocumentCatalog.RepositorySnapshot;
import com.java.semantic.document.catalog.DocumentCatalog.Document;
import com.java.semantic.document.catalog.MarkdownSections.Span;
import com.java.semantic.model.repository.RepositoryId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Coordinates bounded queries while retaining operation admission and a pinned catalog lease. */
public final class DocumentQueryService {
    private record Position(String path, int sectionStartLine) { }

    private final DocumentCatalog catalog;
    private final DocumentCursorCodec cursors;
    private final DocumentOperationGate operations;

    public DocumentQueryService(DocumentCatalog catalog, DocumentCursorCodec cursors,
            DocumentOperationGate operations) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public <T> T search(SearchRequest request, Function<SearchResult, T> materialize) {
        validateSearch(request, materialize);
        try (DocumentOperationGate.Permit permit = operations.open()) {
            DocumentDeadline deadline = permit.deadline();
            deadline.check();
            DocumentCursorCodec.Cursor continuation = request.cursor().isPresent()
                    ? cursors.decode(request.cursor().orElseThrow()) : null;
            if (Objects.nonNull(continuation)
                    && (!continuation.query().equals(request.query()) || continuation.limit() != request.limit()
                            || !continuation.repositoryId().equals(request.repositoryId()))) {
                throw invalid();
            }

            try (ReadLease lease = catalog.open(deadline)) {
                deadline.check();
                CatalogView view = lease.view();
                RepositorySnapshot scoped = request.repositoryId().map(view.repositories()::get).orElse(null);
                String version = request.repositoryId().isEmpty() ? view.globalVersion()
                        : Objects.isNull(scoped) ? "" : scoped.context().documentRevision();
                if (Objects.nonNull(continuation) && !continuation.version().equals(version)) {
                    throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE);
                }
                Map<String, Document> documents = request.repositoryId().isEmpty() ? view.documents()
                        : Objects.isNull(scoped) ? Map.of() : scoped.documents();
                Position after = Objects.isNull(continuation) ? null
                        : new Position(continuation.path(), continuation.sectionStartLine());
                List<SearchHit> candidates = collectCandidates(view, documents, request.query(), view.collectionPurpose(),
                        after, request.limit(), deadline);
                int returned = Math.min(request.limit(), candidates.size());

                while (true) {
                    deadline.check();
                    boolean hasMore = candidates.size() > returned;
                    Optional<String> nextCursor = hasMore
                            ? Optional.of(cursors.encode(new DocumentCursorCodec.Cursor(request.repositoryId(), version,
                                    request.query(), request.limit(), candidates.get(returned).path(),
                                    candidates.get(returned).section().startLine())))
                            : Optional.empty();
                    SearchResult result = new SearchResult(view.documentRepositoryId(), request.repositoryId(), view.collectionPurpose(), Trust.NOT_VERIFIED,
                            view.publicationStatus(), view.publicationErrorCode(), candidates.subList(0, returned),
                            new Page(returned, hasMore, nextCursor));
                    try {
                        T materialized = materialize.apply(result);
                        deadline.check();
                        return materialized;
                    } catch (DocumentFailure failure) {
                        deadline.check();
                        if (failure.code() != DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE || returned <= 1) {
                            throw failure;
                        }
                        returned--;
                    }
                }
            }
        }
    }

    public <T> T read(ReadRequest request, Function<ReadResult, T> materialize) {
        validateRead(request, materialize);
        try (DocumentOperationGate.Permit permit = operations.open()) {
            DocumentDeadline deadline = permit.deadline();
            deadline.check();
            try (ReadLease lease = catalog.open(deadline)) {
                deadline.check();
                CatalogView view = lease.view();
                RepositorySnapshot snapshot = view.requireCurrent(request.documentContext());
                DocumentContext context = snapshot.context();
                Document document = snapshot.documents().get(request.path());
                if (Objects.isNull(document)) {
                    throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_NOT_FOUND);
                }
                Span selected = null;
                for (Span section : document.sections()) {
                    deadline.check();
                    if (section.startLine() == request.sectionStartLine()) {
                        selected = section;
                        break;
                    }
                }
                if (Objects.isNull(selected)) {
                    throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_SECTION_NOT_FOUND);
                }
                ReadResult result = document.readResult(context, selected.section(), view.collectionPurpose(),
                        view.publicationStatus(), view.publicationErrorCode());
                deadline.check();
                T materialized = materialize.apply(result);
                deadline.check();
                return materialized;
            }
        }
    }

    private static List<SearchHit> collectCandidates(CatalogView view, Map<String, Document> documents, String query,
            CollectionPurpose collectionPurpose, Position after, int limit, DocumentDeadline deadline) {
        List<SearchHit> hits = new ArrayList<>(limit + 1);
        for (Map.Entry<String, Document> entry : documents.entrySet()) {
            deadline.check();
            String path = entry.getKey();
            Document document = entry.getValue();
            String content = document.content();
            int nextSearchOffset = 0;
            int matchOffset = -1;
            boolean matchKnown = false;
            for (Span section : document.sections()) {
                deadline.check();
                if (isBefore(path, section.startLine(), after)) {
                    continue;
                }
                if (!matchKnown || matchOffset < section.startOffset()) {
                    matchOffset = content.indexOf(query, Math.max(nextSearchOffset, section.startOffset()));
                    matchKnown = true;
                }
                if (matchOffset < 0) {
                    break;
                }
                if (matchOffset >= section.endOffset()) {
                    continue;
                }
                int excerptEnd = Math.max(section.endOffset(), matchOffset + query.length());
                int availableCodePoints = content.codePointCount(matchOffset, excerptEnd);
                boolean snippetTruncated = availableCodePoints > DocumentContract.MAX_SNIPPET_CODE_POINTS;
                int snippetEnd = snippetTruncated
                        ? content.offsetByCodePoints(matchOffset, DocumentContract.MAX_SNIPPET_CODE_POINTS)
                        : excerptEnd;
                String snippet = content.substring(matchOffset, snippetEnd);
                hits.add(new SearchHit(view.repositories().get(document.primaryContext().repositoryId()).context(), path, document.contentDigest(), section.section(), snippet,
                        snippetTruncated, document.primaryContext(), document.relatedContexts(), document.reviewStatus(),
                        document.reviewReference(), collectionPurpose, Trust.NOT_VERIFIED));
                nextSearchOffset = section.endOffset();
                matchKnown = false;
                if (hits.size() == limit + 1) {
                    return hits;
                }
            }
        }
        return hits;
    }

    private static boolean isBefore(String path, int sectionStartLine, Position after) {
        if (Objects.isNull(after)) {
            return false;
        }
        int pathOrder = path.compareTo(after.path());
        return pathOrder < 0 || pathOrder == 0 && sectionStartLine < after.sectionStartLine();
    }

    private static void validateSearch(SearchRequest request, Function<SearchResult, ?> materialize) {
        if (Objects.isNull(request) || Objects.isNull(materialize) || Objects.isNull(request.query())
                || Objects.isNull(request.cursor()) || Objects.isNull(request.repositoryId()) || request.query().length() > DocumentContract.MAX_QUERY_CODE_POINTS * 2
                || request.limit() < 1 || request.limit() > DocumentContract.MAX_LIMIT) {
            throw invalid();
        }
        try { request.repositoryId().ifPresent(RepositoryId::new); }
        catch (IllegalArgumentException exception) { throw invalid(); }
        String query = request.query();
        int queryCodePoints = query.codePointCount(0, query.length());
        if (queryCodePoints < 1 || queryCodePoints > DocumentContract.MAX_QUERY_CODE_POINTS || query.isBlank()) {
            throw invalid();
        }
        if (request.cursor().isPresent()) {
            String cursor = request.cursor().orElseThrow();
            if (cursor.length() > DocumentContract.MAX_CURSOR_BYTES
                    || cursor.getBytes(StandardCharsets.UTF_8).length > DocumentContract.MAX_CURSOR_BYTES) {
                throw invalid();
            }
        }
    }

    private static void validateRead(ReadRequest request, Function<ReadResult, ?> materialize) {
        if (Objects.isNull(request) || Objects.isNull(materialize) || Objects.isNull(request.documentContext())
                || Objects.isNull(request.path()) || request.path().length() > DocumentContract.MAX_PATH_BYTES
                || request.sectionStartLine() < 1
                || request.path().getBytes(StandardCharsets.UTF_8).length > DocumentContract.MAX_PATH_BYTES) {
            throw invalid();
        }
    }

    private static DocumentFailure invalid() {
        return new DocumentFailure(DocumentFailure.Code.INVALID_ARGUMENT);
    }
}
