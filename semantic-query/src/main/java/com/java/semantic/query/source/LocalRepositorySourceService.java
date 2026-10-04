package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** One admitted revision per operation; the caller obtains it from SourceRevisionCatalog. */
public final class LocalRepositorySourceService implements RepositorySourcePort {
    private final SourceAccessProperties properties;
    private final SourcePathResolver resolver;
    private final BoundedSourceReader reader;
    private final RipgrepTextSearch search;

    public LocalRepositorySourceService(SourceAccessProperties properties, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.resolver = new SourcePathResolver(mapper);
        this.reader = new BoundedSourceReader(properties, resolver);
        this.search = new RipgrepTextSearch(properties, resolver, mapper);
    }

    @Override
    public FileCollection listFiles(AdmittedSourceRevision admitted, FileListRequest request) {
        if (!admitted.context().equals(request.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        String directory = SourcePathResolver.safeDirectory(request.directory());
        long deadline = LocalSourceRevisionCatalog.deadline(properties.listTimeout());
        String binding = QueryCursorCodec.binding("source_files", List.of(admitted.context().repositoryId(),
                admitted.context().revision(), admitted.manifestDigest(), directory, Integer.toString(request.limit())));
        String after = request.cursor().map(cursor -> LocalSourceRevisionCatalog.decode(cursor, binding)).orElse("");
        if (!after.isEmpty()) SourcePathResolver.safeFile(after);
        List<FileEntry> items = new ArrayList<>();
        boolean[] directorySeen = {directory.isEmpty()};
        boolean[] boundarySeen = {after.isEmpty()};
        boolean[] hasMore = {false};
        String[] boundaryKey = {""};
        String prefix = directory.isEmpty() ? "" : directory + "/";
        resolver.scan(admitted, deadline, entry -> {
            if (entry.path().equals(directory) && entry.kind() == EntryKind.DIRECTORY) directorySeen[0] = true;
            if (entry.path().equals(after) && entry.path().startsWith(prefix)
                    && entry.path().substring(prefix.length()).indexOf('/') < 0) {
                boundarySeen[0] = true;
                boundaryKey[0] = SourcePathResolver.key(entry);
            }
            if (!entry.path().startsWith(prefix) || entry.path().equals(directory)) return;
            if (entry.path().substring(prefix.length()).indexOf('/') >= 0) return;
            if (!after.isEmpty() && (!boundarySeen[0]
                    || SourcePathResolver.compare(SourcePathResolver.key(entry), boundaryKey[0]) <= 0)) return;
            if (items.size() == request.limit()) { hasMore[0] = true; return; }
            boolean hint = entry.kind() == EntryKind.FILE && entry.status().orElseThrow() == EntryStatus.TEXT
                    && admitted.manifest().projectGuide().state() == GuideState.AVAILABLE
                    && admitted.manifest().projectGuide().path().filter(entry.path()::equals).isPresent();
            items.add(new FileEntry(entry.path(), entry.kind(), entry.status(), entry.kind() == EntryKind.FILE
                    ? Optional.of(entry.byteLength()) : Optional.empty(), hint));
        });
        if (!directorySeen[0]) throw new SourceQueryException(Code.SOURCE_NOT_FOUND);
        if (!Files.isDirectory(directory.isEmpty() ? admitted.tree() : resolver.physical(admitted, directory),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
        if (!boundarySeen[0]) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        Optional<String> cursor = hasMore[0] ? Optional.of(QueryCursorCodec.encode(binding,
                List.of(items.getLast().path()))) : Optional.empty();
        return new FileCollection(admitted.context(), items, new Page(items.size(), hasMore[0], cursor));
    }

    @Override
    public TextSearchResult searchText(AdmittedSourceRevision admitted, TextSearchRequest request) {
        return search.search(admitted, request);
    }

    @Override
    public SourceResult readSource(AdmittedSourceRevision admitted, ReadSourceRequest request) {
        return reader.read(admitted, request);
    }
}
