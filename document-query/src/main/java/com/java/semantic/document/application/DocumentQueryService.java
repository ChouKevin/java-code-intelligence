package com.java.semantic.document.application;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.Page;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentContract.ReadResult;
import com.java.semantic.document.application.DocumentContract.DocumentListItem;
import com.java.semantic.document.application.DocumentContract.ListRequest;
import com.java.semantic.document.application.DocumentContract.ListResult;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
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
                    && (continuation.operation() != DocumentCursorCodec.Operation.SEARCH
                            || !continuation.query().equals(Optional.of(request.query()))
                            || continuation.limit() != request.limit()
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
                        : new Position(continuation.path(), continuation.sectionStartLine().orElseThrow());
                List<SearchHit> candidates = collectCandidates(view, documents, request.query(), view.collectionPurpose(),
                        after, request.limit(), deadline);
                int returned = Math.min(request.limit(), candidates.size());

                while (true) {
                    deadline.check();
                    boolean hasMore = candidates.size() > returned;
                    Optional<String> nextCursor = hasMore
                            ? Optional.of(cursors.encode(new DocumentCursorCodec.Cursor(DocumentCursorCodec.Operation.SEARCH,
                                    request.repositoryId(), version, Optional.of(request.query()), request.limit(),
                                    candidates.get(returned).path(), Optional.of(candidates.get(returned).section().startLine()))))
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

    public <T> T list(ListRequest request, Function<ListResult, T> materialize) {
        validateList(request, materialize);
        try (DocumentOperationGate.Permit permit = operations.open()) {
            DocumentDeadline deadline = permit.deadline();
            deadline.check();
            DocumentCursorCodec.Cursor continuation = request.cursor().isPresent()
                    ? cursors.decode(request.cursor().orElseThrow()) : null;
            if (Objects.nonNull(continuation)
                    && (continuation.operation() != DocumentCursorCodec.Operation.LIST
                            || continuation.limit() != request.limit()
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
                NavigableMap<String, Document> documents = request.repositoryId().isEmpty() ? view.documents()
                        : Objects.isNull(scoped) ? Collections.emptyNavigableMap() : scoped.documents();
                if (Objects.nonNull(continuation) && !documents.containsKey(continuation.path())) {
                    throw invalid();
                }
                String afterPath = Objects.isNull(continuation) ? null : continuation.path();
                List<DocumentListItem> candidates = collectListItems(view, documents, afterPath, request.limit(), deadline);
                int returned = Math.min(request.limit(), candidates.size());

                while (true) {
                    deadline.check();
                    boolean hasMore = candidates.size() > returned;
                    Optional<String> nextCursor = hasMore
                            ? Optional.of(cursors.encode(new DocumentCursorCodec.Cursor(DocumentCursorCodec.Operation.LIST,
                                    request.repositoryId(), version, Optional.empty(), request.limit(),
                                    candidates.get(returned).path(), Optional.empty())))
                            : Optional.empty();
                    ListResult result = new ListResult(view.documentRepositoryId(), request.repositoryId(),
                            view.collectionPurpose(), Trust.NOT_VERIFIED, view.publicationStatus(),
                            view.publicationErrorCode(), candidates.subList(0, returned), new Page(returned, hasMore, nextCursor));
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

    private static List<DocumentListItem> collectListItems(CatalogView view, NavigableMap<String, Document> documents,
            String afterPath, int limit, DocumentDeadline deadline) {
        List<DocumentListItem> items = new ArrayList<>(limit + 1);
        for (Map.Entry<String, Document> entry : documents.entrySet()) {
            deadline.check();
            String path = entry.getKey();
            if (Objects.nonNull(afterPath) && path.compareTo(afterPath) < 0) {
                continue;
            }
            Document document = entry.getValue();
            RepositorySnapshot owner = view.repositories().get(document.primaryContext().repositoryId());
            items.add(new DocumentListItem(owner.context(), path, document.contentDigest(), document.byteLength(),
                    document.sections().getFirst().section(), document.primaryContext(), document.relatedContexts(),
                    document.reviewStatus(), document.reviewReference()));
            if (items.size() == limit + 1) {
                return items;
            }
        }
        return items;
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

    private static void validateList(ListRequest request, Function<ListResult, ?> materialize) {
        if (Objects.isNull(request) || Objects.isNull(materialize) || Objects.isNull(request.cursor())
                || Objects.isNull(request.repositoryId()) || request.limit() < 1
                || request.limit() > DocumentContract.MAX_LIMIT) {
            throw invalid();
        }
        try { request.repositoryId().ifPresent(RepositoryId::new); }
        catch (IllegalArgumentException exception) { throw invalid(); }
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
