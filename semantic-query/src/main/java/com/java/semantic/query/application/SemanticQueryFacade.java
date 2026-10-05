package com.java.semantic.query.application;

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

/** The sole source application contract shared by HTTP and MCP. */
public final class SemanticQueryFacade {

    private final SourceRevisionCatalog catalog;
    private final RepositorySourcePort source;
    private final Semaphore searchSlots;

    public SemanticQueryFacade(SourceRevisionCatalog catalog, RepositorySourcePort source, int maxActiveSearches) {
        this.catalog = Objects.requireNonNull(catalog, "source revision catalog");
        this.source = Objects.requireNonNull(source, "repository source reader");
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
            default -> throw new IllegalArgumentException("unknown source operation");
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
        return SourceOperationDeadline.within(Duration.ofSeconds(5), () -> {
            if (!searchSlots.tryAcquire()) {
                throw new SourceQueryException(SourceQueryException.Code.SOURCE_BUSY);
            }
            try (AdmittedSourceRevision admitted = catalog.admit(request.context())) {
                return source.searchText(admitted, request);
            }
            finally { searchSlots.release(); }
        });
    }

    public SourceResult readSource(ReadSourceRequest request) {
        return SourceOperationDeadline.within(Duration.ofSeconds(2), () -> {
            try (AdmittedSourceRevision admitted = catalog.admit(request.context())) {
                return source.readSource(admitted, request);
            }
        });
    }
}
