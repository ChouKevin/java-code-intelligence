package com.java.semantic.query.source;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.config.SourceAccessProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** One admitted revision per operation; the caller obtains it from SourceRevisionCatalog. */
public final class LocalRepositorySourceService implements RepositorySourcePort {
    private final BoundedDirectoryReader directories;
    private final BoundedFileFinder finder;
    private final BoundedSourceReader reader;
    private final RipgrepTextSearch search;

    public LocalRepositorySourceService(SourceAccessProperties properties, ObjectMapper mapper) {
        Objects.requireNonNull(properties);
        SourcePathResolver resolver = new SourcePathResolver(mapper);
        this.reader = new BoundedSourceReader(properties, resolver, mapper);
        this.search = new RipgrepTextSearch(properties, resolver, mapper);
        this.directories = new BoundedDirectoryReader(properties, mapper);
        this.finder = new BoundedFileFinder(properties, mapper);
    }

    @Override
    public FileCollection listFiles(AdmittedSourceRevision admitted, FileListRequest request) {
        return directories.read(admitted, request);
    }

    @Override
    public FileFindResult findFiles(AdmittedSourceRevision admitted, FileFindRequest request) {
        return finder.find(admitted, request);
    }

    @Override
    public TextSearchResult searchText(AdmittedSourceRevision admitted, TextSearchRequest request) {
        return search.search(admitted, request);
    }

    @Override
    public SourceResult readSource(AdmittedSourceRevision admitted, ReadSourceRequest request,
            Function<Optional<String>, IndexContract.FoldResult> folds) {
        return reader.read(admitted, request, folds);
    }
}
