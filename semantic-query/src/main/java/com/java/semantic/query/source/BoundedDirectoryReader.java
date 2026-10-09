package com.java.semantic.query.source;

import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.ChildPreview;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileEntry;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
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

/** Reads bounded preorder directory pages from the verified source inventory. */
public final class BoundedDirectoryReader {
    private static final int PREVIEW_CHILD_LIMIT = 12;
    private static final String PREVIEW_POLICY = "inventory-preorder-12-v1";

    private final SourceAccessProperties properties;
    private final ObjectMapper mapper;
    private final SourcePathResolver resolver;

    public BoundedDirectoryReader(SourceAccessProperties properties, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.mapper = Objects.requireNonNull(mapper);
        this.resolver = new SourcePathResolver(mapper);
    }

    public FileCollection read(AdmittedSourceRevision admitted, FileListRequest request) {
        if (!admitted.context().equals(request.context())) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        String directory = SourcePathResolver.safeDirectory(request.directory());
        long deadline = LocalSourceRevisionCatalog.deadline(properties.listTimeout());
        String binding = QueryCursorCodec.binding("source_files", List.of(admitted.context().repositoryId(),
                admitted.context().revision(), admitted.manifestDigest(), directory, Integer.toString(request.depth()),
                PREVIEW_POLICY, Integer.toString(request.limit())));
        Optional<String> after = request.cursor().map(cursor -> decodeCursor(cursor, binding));
        after.ifPresent(BoundedDirectoryReader::validateCursorPosition);

        Listing listing = new Listing(admitted, request, directory, binding, after, deadline);
        resolver.scan(admitted, deadline, listing::accept);
        listing.finish();
        if (!listing.directorySeen()) throw new SourceQueryException(Code.SOURCE_NOT_FOUND);
        Path physicalDirectory = directory.isEmpty() ? admitted.tree() : resolver.physical(admitted, directory);
        if (!Files.isDirectory(physicalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceQueryException(Code.SOURCE_UNAVAILABLE);
        }
        if (!listing.boundarySeen()) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        return listing.collection();
    }

    private static String decodeCursor(String cursor, String binding) {
        try {
            return QueryCursorCodec.decode(cursor, binding, 1).getFirst();
        } catch (IllegalArgumentException exception) {
            throw new SourceQueryException(Code.INVALID_ARGUMENT, exception);
        }
    }

    private static void validateCursorPosition(String position) {
        boolean directory = position.endsWith("/");
        String path = directory ? position.substring(0, position.length() - 1) : position;
        if (path.isEmpty()) throw new SourceQueryException(Code.INVALID_ARGUMENT);
        SourcePathResolver.safeFile(path);
    }

    private final class Listing {
        private final AdmittedSourceRevision admitted;
        private final FileListRequest request;
        private final String directory;
        private final String prefix;
        private final String binding;
        private final Optional<String> after;
        private final long deadline;
        private final List<PageItem> items = new ArrayList<>();
        private PendingDirectory pendingDirectory;
        private boolean directorySeen;
        private boolean boundarySeen;
        private boolean hasMore;
        private boolean stopped;

        private Listing(AdmittedSourceRevision admitted, FileListRequest request, String directory,
                String binding, Optional<String> after, long deadline) {
            this.admitted = admitted;
            this.request = request;
            this.directory = directory;
            this.prefix = directory.isEmpty() ? "" : directory + "/";
            this.binding = binding;
            this.after = after;
            this.deadline = deadline;
            this.directorySeen = directory.isEmpty();
            this.boundarySeen = after.isEmpty();
        }

        private void accept(SourceInventoryEntry entry) {
            LocalSourceRevisionCatalog.check(deadline);
            if (entry.path().equals(directory) && entry.kind() == EntryKind.DIRECTORY) directorySeen = true;

            if (Objects.nonNull(pendingDirectory)) {
                String pendingPrefix = pendingDirectory.entry.path() + "/";
                if (entry.path().startsWith(pendingPrefix)) {
                    String relative = entry.path().substring(pendingPrefix.length());
                    if (!relative.isEmpty() && relative.indexOf('/') < 0) pendingDirectory.addChild(entry);
                    return;
                }
                flushPendingDirectory();
            }

            if (!entry.path().startsWith(prefix) || entry.path().equals(directory)) return;
            String relative = entry.path().substring(prefix.length());
            if (relative.isEmpty() || relative.indexOf('/') >= 0) return;
            if (entry.kind() == EntryKind.DIRECTORY && request.depth() == 2) {
                pendingDirectory = new PendingDirectory(entry);
                return;
            }
            emit(entry, 1, ChildPreview.NOT_EXPANDED);
        }

        private void finish() {
            flushPendingDirectory();
        }

        private void flushPendingDirectory() {
            PendingDirectory pending = pendingDirectory;
            if (Objects.isNull(pending)) return;
            pendingDirectory = null;
            ChildPreview preview = pending.elided ? ChildPreview.ELIDED : ChildPreview.COMPLETE;
            emit(pending.entry, 1, preview);
            for (SourceInventoryEntry child : pending.children) {
                if (stopped) break;
                emit(child, 2, ChildPreview.NOT_EXPANDED);
            }
        }

        private void emit(SourceInventoryEntry entry, int depth, ChildPreview preview) {
            String position = SourcePathResolver.key(entry);
            if (!boundarySeen) {
                if (after.orElseThrow().equals(position)) boundarySeen = true;
                return;
            }
            if (stopped) return;
            if (items.size() == request.limit()) {
                hasMore = true;
                stopped = true;
                return;
            }
            FileEntry item = fileEntry(entry, depth, preview);
            items.add(new PageItem(item, SourceResponseBudget.encodedBytes(item, mapper), position));
        }

        private FileEntry fileEntry(SourceInventoryEntry entry, int depth, ChildPreview preview) {
            Optional<ChildPreview> childPreview = entry.kind() == EntryKind.DIRECTORY
                    ? Optional.of(preview) : Optional.empty();
            Optional<Long> byteLength = entry.kind() == EntryKind.FILE
                    ? Optional.of(entry.byteLength()) : Optional.empty();
            boolean navigationHint = entry.kind() == EntryKind.FILE
                    && entry.status().orElseThrow() == EntryStatus.TEXT
                    && admitted.manifest().projectGuide().state()
                            == GuideState.AVAILABLE
                    && admitted.manifest().projectGuide().path().filter(entry.path()::equals).isPresent();
            return new FileEntry(entry.path(), entry.kind(), entry.status(), byteLength, navigationHint, depth, childPreview);
        }

        private boolean directorySeen() {
            return directorySeen;
        }

        private boolean boundarySeen() {
            return boundarySeen;
        }

        private FileCollection collection() {
            Page emptyPage = new Page(0, false, Optional.empty());
            int envelopeBytes = SourceResponseBudget.encodedBytes(new FileCollection(admitted.context(), List.of(), emptyPage), mapper)
                    - SourceResponseBudget.encodedBytes(emptyPage, mapper);
            boolean sizeLimited = false;
            long itemBytes = items.stream().mapToLong(PageItem::wireBytes).sum();
            while (true) {
                LocalSourceRevisionCatalog.check(deadline);
                boolean more = hasMore || sizeLimited;
                if (more && items.isEmpty()) throw tooLarge();
                Optional<String> cursor = more ? Optional.of(cursor(items.getLast().position())) : Optional.empty();
                Page page = new Page(items.size(), more, cursor);
                long separators = 2L * Math.max(0, items.size() - 1);
                long candidateBytes = envelopeBytes + itemBytes + separators + SourceResponseBudget.encodedBytes(page, mapper);
                if (candidateBytes <= SourceReadContract.MAX_READ_RESPONSE_BYTES - SourceResponseBudget.ENVELOPE_RESERVE_BYTES) {
                    List<FileEntry> entries = items.stream().map(PageItem::entry).toList();
                    FileCollection result = new FileCollection(admitted.context(), entries, page);
                    if (SourceResponseBudget.fits(result, mapper)) {
                        LocalSourceRevisionCatalog.check(deadline);
                        return result;
                    }
                }
                if (items.size() <= 1) throw tooLarge();
                itemBytes -= items.removeLast().wireBytes();
                sizeLimited = true;
            }
        }

        private String cursor(String position) {
            try {
                return QueryCursorCodec.encode(binding, List.of(position));
            } catch (IllegalArgumentException exception) {
                throw new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE, exception);
            }
        }

        private SourceQueryException tooLarge() {
            return new SourceQueryException(Code.SOURCE_RESULT_TOO_LARGE);
        }
    }

    private static final class PendingDirectory {
        private final SourceInventoryEntry entry;
        private final List<SourceInventoryEntry> children = new ArrayList<>(PREVIEW_CHILD_LIMIT);
        private boolean elided;

        private PendingDirectory(SourceInventoryEntry entry) {
            this.entry = entry;
        }

        private void addChild(SourceInventoryEntry child) {
            if (children.size() < PREVIEW_CHILD_LIMIT) children.add(child);
            else elided = true;
        }
    }

    private record PageItem(FileEntry entry, int wireBytes, String position) { }
}
