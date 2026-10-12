package com.java.semantic.model.source;

import com.java.semantic.model.index.IndexContract.IndexSummary;
import com.java.semantic.model.index.IndexContract.Profile;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRevisionManifest.Coverage;
import com.java.semantic.model.support.ModelValidation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SourceReadContract {

    public static final int DEFAULT_PAGE_LIMIT = 20;
    public static final int MAX_PAGE_LIMIT = 100;
    public static final int MAX_QUERY_CODE_POINTS = 256;
    public static final int DEFAULT_READ_LINES = 200;
    public static final int MAX_READ_LINES = 500;
    public static final int DEFAULT_CONTENT_BYTES = 65_536;
    public static final int MAX_READ_RESPONSE_BYTES = 524_288;
    public static final int MAX_REPOSITORY_RESPONSE_BYTES = 160 * 1024;

    private SourceReadContract() { }

    public record RepositoryRequest(Optional<String> nameFilter, int limit, Optional<String> cursor) {
        public RepositoryRequest {
            nameFilter = Objects.requireNonNull(nameFilter, "name filter");
            nameFilter.ifPresent(SourceReadContract::requireUnicode);
            requireLimit(limit);
            cursor = requireCursor(cursor);
        }
    }

    public record ContextRequest(String repositoryId, Optional<String> revision) {
        public ContextRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            revision = Objects.requireNonNull(revision, "requested revision");
            revision.ifPresent(RepositoryRevision::ofSha);
        }
    }

    public record FileListRequest(SourceContext context, String directory, int depth, int limit, Optional<String> cursor) {
        public FileListRequest {
            context = Objects.requireNonNull(context, "source context");
            directory = SourcePathPolicy.requireDirectory(directory);
            ModelValidation.require(depth == 1 || depth == 2, "directory depth must be one or two");
            requireLimit(limit);
            cursor = requireCursor(cursor);
        }
    }

    public record FileFindRequest(SourceContext context, String filePattern, String directory,
            int limit, Optional<String> cursor) {
        public FileFindRequest {
            context = Objects.requireNonNull(context, "source context");
            filePattern = requirePattern(filePattern);
            directory = SourcePathPolicy.requireDirectory(directory);
            requireLimit(limit);
            cursor = requireCursor(cursor);
        }
    }

    public record TextSearchRequest(SourceContext context, String query, String directory,
            Optional<String> filePattern, int limit) {
        public TextSearchRequest {
            context = Objects.requireNonNull(context, "source context");
            query = requireUnicode(query);
            ModelValidation.require(!query.isEmpty() && query.codePointCount(0, query.length()) <= MAX_QUERY_CODE_POINTS
                            && query.indexOf('\n') < 0 && query.indexOf('\r') < 0 && query.indexOf('\0') < 0,
                    "query must be a nonempty single-line literal of at most 256 Unicode code points");
            directory = SourcePathPolicy.requireDirectory(directory);
            filePattern = Objects.requireNonNull(filePattern, "file pattern");
            filePattern.ifPresent(SourceReadContract::requirePattern);
            requireLimit(limit);
        }
    }

    public record ReadSourceRequest(SourceContext context, String path, View view,
            Optional<Integer> startLine, List<LineRange> ranges, Optional<Integer> tailLines,
            int maxLines, int contextBefore, int contextAfter, Optional<String> indexGeneration,
            Optional<String> cursor) {
        public ReadSourceRequest {
            context = Objects.requireNonNull(context, "source context");
            path = SourcePathPolicy.requireFile(path);
            view = Objects.requireNonNull(view, "view");
            startLine = Objects.requireNonNull(startLine, "start line");
            ranges = Objects.requireNonNull(ranges, "ranges");
            ModelValidation.require(ranges.size() <= 20, "at most twenty ranges are allowed");
            ranges = List.copyOf(ranges);
            tailLines = Objects.requireNonNull(tailLines, "tail lines");
            startLine.ifPresent(line -> ModelValidation.require(line >= 1, "start line must be positive"));
            tailLines.ifPresent(lines -> ModelValidation.require(lines >= 1 && lines <= 500, "tail lines must be 1..500"));
            int selectors = (startLine.isPresent() ? 1 : 0) + (!ranges.isEmpty() ? 1 : 0) + (tailLines.isPresent() ? 1 : 0);
            ModelValidation.require(selectors <= 1 && (view != View.SUMMARY || selectors == 0), "ambiguous source selection");
            ModelValidation.require(maxLines >= 1 && maxLines <= MAX_READ_LINES, "read line limit must be 1..500");
            ModelValidation.require(contextBefore >= 0 && contextBefore <= 20 && contextAfter >= 0 && contextAfter <= 20,
                    "context must be 0..20");
            ModelValidation.require((!ranges.isEmpty() && view != View.RAW) || contextBefore == 0 && contextAfter == 0,
                    "context requires non-raw ranges");
            indexGeneration = Objects.requireNonNull(indexGeneration, "index generation");
            if (indexGeneration.isPresent()) {
                String value = indexGeneration.orElseThrow();
                ModelValidation.require(view == View.SUMMARY, "generation requires summary");
                ModelValidation.require(java.util.UUID.fromString(value).toString().equals(value), "generation must be canonical UUID");
            }
            cursor = requireCursor(cursor);
        }
        public boolean hasSelector() { return startLine.isPresent() || !ranges.isEmpty() || tailLines.isPresent(); }
    }

    public record LineRange(int startLine, int endLine) {
        public LineRange {
            ModelValidation.require(startLine >= 1 && endLine >= startLine, "line range must be positive and ordered");
        }
    }

    public record Selection(Optional<Integer> startLine, List<LineRange> ranges, Optional<Integer> tailLines,
            int contextBefore, int contextAfter) {
        public Selection {
            startLine = Objects.requireNonNull(startLine);
            ranges = List.copyOf(ranges);
            tailLines = Objects.requireNonNull(tailLines);
        }
    }

    public record RepositoryCollection(List<RepositoryItem> items, Page page) {
        public RepositoryCollection {
            items = List.copyOf(Objects.requireNonNull(items, "repository items"));
            page = Objects.requireNonNull(page, "repository page");
            ModelValidation.require(items.size() == page.returned(), "repository page must describe returned items");
        }
    }

    public record RepositoryItem(String repositoryId, String displayName, String defaultBranch,
            Optional<String> projectGuidePath, SourceStatus sourceStatus, SemanticStatus semanticStatus,
            Optional<String> revision, Optional<RepositoryNavigationHint> navigationHint) {
        public RepositoryItem {
            repositoryId = new RepositoryId(repositoryId).value();
            displayName = ModelValidation.requiredText(displayName, "display name");
            defaultBranch = ModelValidation.requiredText(defaultBranch, "default branch");
            projectGuidePath = Objects.requireNonNull(projectGuidePath, "project guide path");
            projectGuidePath.ifPresent(SourcePathPolicy::requireFile);
            sourceStatus = Objects.requireNonNull(sourceStatus, "source status");
            semanticStatus = Objects.requireNonNull(semanticStatus, "semantic status");
            revision = Objects.requireNonNull(revision, "published revision");
            revision.ifPresent(RepositoryRevision::ofSha);
            navigationHint = Objects.requireNonNull(navigationHint, "navigation hint");
            ModelValidation.require((sourceStatus == SourceStatus.READY) == revision.isPresent(),
                    "only ready repositories have a published revision");
        }
    }

    public record ContextResult(String repositoryId, String defaultBranch, SourceStatus sourceStatus,
            SemanticStatus semanticStatus, Optional<SourceContext> context, Optional<GuideInfo> projectGuide,
            Optional<Coverage> coverage, Optional<IndexSummary> index) {
        public ContextResult {
            repositoryId = new RepositoryId(repositoryId).value();
            defaultBranch = ModelValidation.requiredText(defaultBranch, "default branch");
            sourceStatus = Objects.requireNonNull(sourceStatus, "source status");
            semanticStatus = Objects.requireNonNull(semanticStatus, "semantic status");
            context = Objects.requireNonNull(context, "source context");
            projectGuide = Objects.requireNonNull(projectGuide, "project guide");
            coverage = Objects.requireNonNull(coverage, "source coverage");
            index = Objects.requireNonNull(index, "index summary");
            ModelValidation.require((semanticStatus == SemanticStatus.READY) == index.isPresent()
                            && (semanticStatus != SemanticStatus.READY || sourceStatus == SourceStatus.READY),
                    "ready index discovery requires exact ready source and index summary");
            boolean ready = sourceStatus == SourceStatus.READY;
            ModelValidation.require(ready == context.isPresent() && ready == projectGuide.isPresent()
                            && ready == coverage.isPresent(),
                    "only ready contexts have published identity and manifest metadata");
            if (context.isPresent()) {
                ModelValidation.require(repositoryId.equals(context.orElseThrow().repositoryId()),
                        "context repository must match discovery repository");
            }
        }
    }

    public record FileCollection(SourceContext context, List<FileEntry> items, Page page) {
        public FileCollection {
            context = Objects.requireNonNull(context, "source context");
            items = List.copyOf(Objects.requireNonNull(items, "file entries"));
            page = Objects.requireNonNull(page, "file page");
            ModelValidation.require(items.size() == page.returned(), "file page must describe returned entries");
        }
    }

    public record FoundFile(String path, EntryStatus status, long byteLength, boolean navigationHint) {
        public FoundFile {
            path = SourcePathPolicy.requireFile(path);
            status = Objects.requireNonNull(status, "entry status");
            ModelValidation.require(byteLength >= 0, "byte length must not be negative");
            ModelValidation.require(!navigationHint || status == EntryStatus.TEXT,
                    "navigation hints require text source");
        }
    }

    public record FileFindResult(SourceContext context, List<FoundFile> items, Page page) {
        public FileFindResult {
            context = Objects.requireNonNull(context, "source context");
            items = List.copyOf(Objects.requireNonNull(items, "found files"));
            page = Objects.requireNonNull(page, "file page");
            ModelValidation.require(items.size() == page.returned(), "file page must describe returned entries");
            ModelValidation.require(!page.hasMore() || !items.isEmpty(), "continuation must advance");
        }
    }

    public record FileEntry(String path, EntryKind kind, Optional<EntryStatus> status, Optional<Long> byteLength,
            boolean navigationHint, int depth, Optional<ChildPreview> childPreview) {
        public FileEntry {
            path = SourcePathPolicy.requireFile(path);
            kind = Objects.requireNonNull(kind, "entry kind");
            status = Objects.requireNonNull(status, "entry status");
            byteLength = Objects.requireNonNull(byteLength, "byte length");
            ModelValidation.require(depth == 1 || depth == 2, "entry depth must be one or two");
            childPreview = Objects.requireNonNull(childPreview, "child preview");
            ModelValidation.require((kind == EntryKind.DIRECTORY) == childPreview.isPresent(),
                    "only directories have child preview status");
            byteLength.ifPresent(length -> ModelValidation.require(length >= 0, "byte length must not be negative"));
            ModelValidation.require(kind == EntryKind.FILE ? status.isPresent() && byteLength.isPresent()
                            : status.isEmpty() && byteLength.isEmpty() && !navigationHint,
                    "file metadata and directory metadata must remain distinct");
        }
    }

    public record TextSearchResult(SourceContext context, List<TextMatch> matches, boolean truncated,
            boolean scanComplete) {
        public TextSearchResult {
            context = Objects.requireNonNull(context, "source context");
            matches = List.copyOf(Objects.requireNonNull(matches, "text matches"));
            ModelValidation.require(matches.size() <= MAX_PAGE_LIMIT, "search matches exceed the result limit");
            ModelValidation.require(truncated != scanComplete, "successful search must be complete or truncated");
        }
    }

    public record TextMatch(String path, int line, int column, String matchedText, boolean navigationHint) {
        public TextMatch {
            path = SourcePathPolicy.requireFile(path);
            ModelValidation.require(line >= 1 && column >= 1, "source coordinates must be positive");
            matchedText = Objects.requireNonNull(matchedText, "matched text");
        }
    }

    public record SourceResult(SourceContext context, String path, String blobId, String contentDigest,
            long byteLength, View requestedView, View effectiveView, Selection requestedSelection,
            List<LineRange> effectiveSelection, SummaryOutcome summaryOutcome, Optional<String> indexGeneration,
            Optional<Profile> profile,
            List<Segment> segments, boolean hasMore, Optional<String> nextCursor, StopReason stopReason,
            boolean navigationHint) {
        public SourceResult {
            context = Objects.requireNonNull(context);
            path = SourcePathPolicy.requireFile(path);
            blobId = RepositoryRevision.ofSha(blobId).value();
            contentDigest = ModelValidation.sha256(contentDigest, "source digest");
            ModelValidation.require(byteLength >= 0, "byte length must be nonnegative");
            requestedView = Objects.requireNonNull(requestedView);
            effectiveView = Objects.requireNonNull(effectiveView);
            requestedSelection = Objects.requireNonNull(requestedSelection);
            effectiveSelection = List.copyOf(effectiveSelection);
            summaryOutcome = Objects.requireNonNull(summaryOutcome);
            indexGeneration = Objects.requireNonNull(indexGeneration);
            profile = Objects.requireNonNull(profile);
            segments = List.copyOf(segments);
            ModelValidation.require(segments.size() <= 500, "too many segments");
            nextCursor = requireCursor(nextCursor);
            ModelValidation.require(hasMore == nextCursor.isPresent(), "continuation requires cursor");
            stopReason = Objects.requireNonNull(stopReason);
            ModelValidation.require(effectiveView != View.AUTO && (effectiveView == View.SUMMARY) == indexGeneration.isPresent()
                    && indexGeneration.isPresent() == profile.isPresent(), "effective view and index provenance must agree");
            indexGeneration.ifPresent(value -> ModelValidation.require(java.util.UUID.fromString(value).toString().equals(value),
                    "generation must be canonical UUID"));
            long previous = -1;
            for (Segment segment : segments) {
                ModelValidation.require(segment.startByte() >= previous && segment.endByte() <= byteLength,
                        "segments must be ordered within source");
                previous = segment.endByte();
            }
            ModelValidation.require(!hasMore || !segments.isEmpty(), "continuation must advance");
        }
    }

    public record Segment(SegmentKind kind, long startByte, long endByte, int startLine, int endLine,
            Optional<String> content, boolean startsMidLine, boolean endsMidLine,
            Optional<String> foldKind, Optional<String> declarationId) {
        public Segment {
            kind = Objects.requireNonNull(kind);
            ModelValidation.require(startByte >= 0 && endByte > startByte && startLine >= 1 && endLine >= startLine,
                    "segment coordinates must be ordered");
            content = Objects.requireNonNull(content);
            foldKind = Objects.requireNonNull(foldKind);
            declarationId = Objects.requireNonNull(declarationId);
            ModelValidation.require(kind == SegmentKind.SOURCE ? content.isPresent() && foldKind.isEmpty() && declarationId.isEmpty()
                    : content.isEmpty() && foldKind.isPresent() && declarationId.isPresent(), "segment payload must match kind");
        }
    }

    public enum View { AUTO, RAW, SUMMARY }
    public enum SegmentKind { SOURCE, ELISION }
    public enum StopReason { COMPLETE, LINE_BUDGET, CONTENT_BUDGET, SEGMENT_BUDGET, WIRE_BUDGET }
    public enum SummaryOutcome {
        NOT_REQUESTED, ELIDED, NO_ELISION, BELOW_SUMMARY_THRESHOLD, UNSUPPORTED_LANGUAGE,
        INDEX_NOT_READY, INDEX_UNAVAILABLE, FOLD_METADATA_UNAVAILABLE, FILE_PARSE_FAILED, FILE_UNSUPPORTED, FILE_TOO_LARGE
    }
    public enum ChildPreview { COMPLETE, ELIDED, NOT_EXPANDED }

    public record Page(int returned, boolean hasMore, Optional<String> nextCursor) {
        public Page {
            ModelValidation.require(returned >= 0 && returned <= MAX_PAGE_LIMIT,
                    "returned count must be between 0 and 100");
            nextCursor = requireCursor(nextCursor);
            ModelValidation.require(hasMore == nextCursor.isPresent(), "pagination requires a next cursor");
        }
    }

    public record GuideInfo(GuideState state, Optional<String> path, Optional<String> digest,
            GuideFreshness freshness) {
        public GuideInfo {
            state = Objects.requireNonNull(state, "guide state");
            path = Objects.requireNonNull(path, "guide path");
            path.ifPresent(SourcePathPolicy::requireFile);
            digest = Objects.requireNonNull(digest, "guide digest");
            digest.ifPresent(value -> ModelValidation.sha256(value, "guide digest"));
            freshness = Objects.requireNonNull(freshness, "guide freshness");
            ModelValidation.require((state == GuideState.AVAILABLE) == digest.isPresent(),
                    "only an available guide has a content digest");
            ModelValidation.require(state != GuideState.AVAILABLE || path.isPresent(),
                    "an available guide requires its exact source path");
            ModelValidation.require(state != GuideState.DISABLED || path.isEmpty(),
                    "a disabled guide has no configured path");
        }
    }

    public enum SourceStatus { NOT_PREPARED, PREPARING, FAILED, READY }
    public enum SemanticStatus { NOT_READY, READY, UNAVAILABLE }
    public enum EntryKind { DIRECTORY, FILE }
    public enum EntryStatus { TEXT, BINARY, UNSUPPORTED_ENCODING, UNSUPPORTED_PATH, SYMLINK, SUBMODULE, LFS_POINTER }
    public enum GuideState { AVAILABLE, MISSING, INVALID, DISABLED }
    public enum GuideFreshness { NOT_VERIFIED }

    private static void requireLimit(int limit) {
        ModelValidation.require(limit >= 1 && limit <= MAX_PAGE_LIMIT, "page limit must be between 1 and 100");
    }

    private static Optional<String> requireCursor(Optional<String> cursor) {
        Objects.requireNonNull(cursor, "cursor");
        cursor.ifPresent(value -> ModelValidation.requiredText(value, "cursor"));
        return cursor;
    }

    private static String requirePattern(String pattern) {
        requireUnicode(pattern);
        ModelValidation.require(!pattern.isBlank() && pattern.codePointCount(0, pattern.length()) <= 256
                        && pattern.codePoints().noneMatch(Character::isISOControl),
                "file pattern must be nonblank valid Unicode of at most 256 scalar values without controls");
        return pattern;
    }

    private static String requireUnicode(String value) {
        Objects.requireNonNull(value, "text");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                ModelValidation.require(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)),
                        "text must contain valid Unicode");
                index++;
            } else {
                ModelValidation.require(!Character.isLowSurrogate(character), "text must contain valid Unicode");
            }
        }
        return value;
    }
}
