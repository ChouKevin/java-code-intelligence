package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.query.index.RepositoryIndexPort;
import com.java.semantic.model.source.SourceReadContract.ContextRequest;
import com.java.semantic.model.source.SourceReadContract.ContextResult;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.RepositoryCollection;
import com.java.semantic.model.source.SourceReadContract.RepositoryRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchResult;
import com.java.semantic.query.source.RepositorySourcePort;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.SourceRevisionCatalog;
import com.java.semantic.query.source.SourceQueryException;
import com.java.semantic.query.source.SourceOperationDeadline;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** The sole source/index application contract shared by HTTP and MCP. */
public final class SemanticQueryFacade {

    private final SourceRevisionCatalog catalog;
    private final RepositorySourcePort source;
    private final RepositoryIndexPort index;
    private final Semaphore searchSlots;

    public SemanticQueryFacade(SourceRevisionCatalog catalog, RepositorySourcePort source,
            RepositoryIndexPort index, int maxActiveSearches) {
        this.catalog = Objects.requireNonNull(catalog, "source revision catalog");
        this.source = Objects.requireNonNull(source, "repository source reader");
        this.index = Objects.requireNonNull(index, "repository index reader");
        if (maxActiveSearches < 1 || maxActiveSearches > 2) {
            throw new IllegalArgumentException("active search limit must be between one and two");
        }
        this.searchSlots = new Semaphore(maxActiveSearches);
    }

    public Object execute(String operation, Map<String, ?> input) {
        return switch (Objects.requireNonNull(operation, "operation")) {
            case "list_repositories" -> listRepositories(SemanticQueryInput.repositories(input));
            case "get_context" -> getContext(SemanticQueryInput.getContext(input));
            case "list_files" -> listFiles(SemanticQueryInput.listFiles(input));
            case "search_text" -> searchText(SemanticQueryInput.searchText(input));
            case "read_source" -> readSource(SemanticQueryInput.readSource(input));
            case "search_code" -> searchCode(IndexQueryInput.searchCode(input));
            case "get_outline" -> getOutline(IndexQueryInput.outline(input));
            case "list_entry_points" -> listEntryPoints(IndexQueryInput.entryPoints(input));
            default -> throw new IllegalArgumentException("unknown query operation");
        };
    }

    public RepositoryCollection listRepositories(RepositoryRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(2), () -> catalog.listRepositories(request));
    }

    public ContextResult getContext(ContextRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(2), () -> catalog.getContext(request));
    }

    public FileCollection listFiles(FileListRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(2), () -> {
            try (AdmittedSourceRevision admitted = catalog.admit(request.context())) {
                return source.listFiles(admitted, request);
            }
        });
    }

    public TextSearchResult searchText(TextSearchRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(5),
                () -> admittedSearch(request.context(), false, admitted -> source.searchText(admitted, request)));
    }

    public SourceResult readSource(ReadSourceRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(2), () -> {
            try (AdmittedSourceRevision admitted = catalog.admit(request.context())) {
                return source.readSource(admitted, request, generation -> {
                    if (!searchSlots.tryAcquire()) throw new IndexFailure(IndexFailure.Code.INDEX_BUSY);
                    admitted.lease().onRelease(searchSlots::release);
                    return index.folds(admitted, request.path(), generation);
                });
            }
        });
    }

    public IndexContract.Result<IndexContract.Declaration> searchCode(IndexContract.SearchCodeRequest request) {
        return indexSearch(request.context(), admitted -> index.searchCode(admitted, request));
    }

    public IndexContract.Result<IndexContract.Declaration> getOutline(IndexContract.OutlineRequest request) {
        return indexSearch(request.context(), admitted -> index.outline(admitted, request));
    }

    public IndexContract.Result<IndexContract.EntryCandidate> listEntryPoints(IndexContract.EntryPointsRequest request) {
        return indexSearch(request.context(), admitted -> index.entryPoints(admitted, request));
    }

    private <T> T indexSearch(SourceContext context, Function<AdmittedSourceRevision, T> operation) {
        return SourceOperationDeadline.within(Duration.ofSeconds(5),
                () -> admittedSearch(context, true, operation),
                () -> new IndexFailure(IndexFailure.Code.INDEX_TIMEOUT));
    }

    private <T> T admittedSearch(SourceContext context, boolean indexOperation,
            Function<AdmittedSourceRevision, T> operation) {
        if (!searchSlots.tryAcquire()) {
            if (indexOperation) throw new IndexFailure(IndexFailure.Code.INDEX_BUSY);
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_BUSY);
        }
        boolean admittedSlot = false;
        try (AdmittedSourceRevision admitted = catalog.admit(context)) {
            admitted.lease().onRelease(searchSlots::release);
            admittedSlot = true;
            return operation.apply(admitted);
        } finally {
            if (!admittedSlot) searchSlots.release();
        }
    }
}
