package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.FileFindRequest;
import com.java.semantic.model.source.SourceReadContract.FileFindResult;
import com.java.semantic.model.source.SourceReadContract.FoundFile;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceReadContract.Page;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceQueryException.Code;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Bounded file discovery over publication membership, never physical file enumeration. */
public final class BoundedFileFinder {
    private static final String POLICY = "source-glob-1";
    private final SourceAccessProperties properties;
    private final ObjectMapper mapper;
    private final SourcePathResolver resolver;

    public BoundedFileFinder(SourceAccessProperties properties, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.mapper = Objects.requireNonNull(mapper);
        this.resolver = new SourcePathResolver(mapper);
    }

    public FileFindResult find(AdmittedSourceRevision admitted, FileFindRequest request) {
        if (!admitted.context().equals(request.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        long deadline = LocalSourceRevisionCatalog.deadline(properties.searchTimeout());
        String directory = SourcePathResolver.safeDirectory(request.directory());
        SourceGlob.Matcher matcher = SourceGlob.compile(request.filePattern()).matcher(deadline);
        String binding = QueryCursorCodec.binding("source_find_files", List.of(POLICY,
                admitted.context().repositoryId(), admitted.context().revision(), admitted.manifestDigest(),
                request.filePattern(), directory, Integer.toString(request.limit())));
        Optional<String> after = request.cursor().map(cursor -> decode(cursor, binding));
        after.ifPresent(SourcePathResolver::safeFile);
        Listing listing = new Listing(admitted, request, directory, matcher, binding, after, deadline);
        // Even after lookahead is known, scan must authenticate every inventory byte/order to EOF.
        resolver.scan(admitted, deadline, listing::accept);
        if (!listing.directorySeen) throw new SourceQueryException(Code.SOURCE_NOT_FOUND);
        Path physical = directory.isEmpty() ? admitted.tree() : resolver.physical(admitted, directory);
        LocalSourceRevisionCatalog.noLinks(physical);
        if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)) throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        if (!listing.boundarySeen) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        return listing.result();
    }

    private static String decode(String cursor, String binding) {
        try { return SourceReadCursorCodec.decode(cursor, binding, 1).getFirst(); }
        catch (IllegalArgumentException exception) { throw new SourceQueryException(Code.INVALID_ARGUMENT, exception); }
    }

    private final class Listing {
        private final AdmittedSourceRevision admitted;
        private final FileFindRequest request;
        private final String directory;
        private final String prefix;
        private final SourceGlob.Matcher matcher;
        private final String binding;
        private final Optional<String> after;
        private final long deadline;
        private final List<PageItem> items = new ArrayList<>();
        private boolean directorySeen;
        private boolean boundarySeen;
        private boolean hasMore;

        private Listing(AdmittedSourceRevision admitted, FileFindRequest request, String directory,
                SourceGlob.Matcher matcher, String binding, Optional<String> after, long deadline) {
            this.admitted = admitted;
            this.request = request;
            this.directory = directory;
            this.prefix = directory.isEmpty() ? "" : directory + "/";
            this.matcher = matcher;
            this.binding = binding;
            this.after = after;
            this.deadline = deadline;
            directorySeen = directory.isEmpty();
            boundarySeen = after.isEmpty();
        }

        private void accept(SourceInventoryEntry entry) {
            if (entry.kind() == EntryKind.DIRECTORY && entry.path().equals(directory)) directorySeen = true;
            if (hasMore || entry.kind() != EntryKind.FILE || !entry.path().startsWith(prefix)) return;
            if (!matcher.matches(entry.path())) return;
            if (!boundarySeen) {
                if (after.orElseThrow().equals(entry.path())) boundarySeen = true;
                return;
            }
            if (items.size() == request.limit()) { hasMore = true; return; }
            EntryStatus status = entry.status().orElseThrow();
            boolean hint = status == EntryStatus.TEXT && admitted.manifest().projectGuide().state() == GuideState.AVAILABLE
                    && admitted.manifest().projectGuide().path().filter(entry.path()::equals).isPresent();
            FoundFile file = new FoundFile(entry.path(), status, entry.byteLength(), hint);
            items.add(new PageItem(file, SourceResponseBudget.encodedBytes(file, mapper)));
        }

        private FileFindResult result() {
            Page empty = new Page(0, false, Optional.empty());
            int envelopeBytes = SourceResponseBudget.encodedBytes(new FileFindResult(admitted.context(), List.of(), empty), mapper)
                    - SourceResponseBudget.encodedBytes(empty, mapper);
            long itemBytes = items.stream().mapToLong(PageItem::wireBytes).sum();
            boolean sizeLimited = false;
            while (true) {
                LocalSourceRevisionCatalog.check(deadline);
                boolean more = hasMore || sizeLimited;
                if (more && items.isEmpty()) throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE);
                Optional<String> cursor;
                try {
                    cursor = more ? Optional.of(SourceReadCursorCodec.encode(binding, List.of(items.getLast().file.path())))
                            : Optional.empty();
                } catch (IllegalArgumentException exception) {
                    if (items.size() <= 1) throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE, exception);
                    itemBytes -= items.removeLast().wireBytes;
                    sizeLimited = true;
                    continue;
                }
                Page page = new Page(items.size(), more, cursor);
                long candidateBytes = envelopeBytes + itemBytes + 2L * Math.max(0, items.size() - 1)
                        + SourceResponseBudget.encodedBytes(page, mapper);
                if (candidateBytes <= SourceReadContract.MAX_READ_RESPONSE_BYTES - SourceResponseBudget.ENVELOPE_RESERVE_BYTES) {
                    FileFindResult result = new FileFindResult(admitted.context(), items.stream().map(PageItem::file).toList(), page);
                    if (SourceResponseBudget.fits(result, mapper)) {
                        LocalSourceRevisionCatalog.check(deadline);
                        return result;
                    }
                }
                if (items.size() <= 1) throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE);
                itemBytes -= items.removeLast().wireBytes;
                sizeLimited = true;
            }
        }

    }

    private record PageItem(FoundFile file, int wireBytes) { }
}
