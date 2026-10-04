package com.java.semantic.model.source;

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

    public record FileListRequest(SourceContext context, String directory, int limit, Optional<String> cursor) {
        public FileListRequest {
            context = Objects.requireNonNull(context, "source context");
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
            filePattern.ifPresent(pattern -> {
                SourcePathPolicy.requireFile(pattern);
                ModelValidation.require(!pattern.startsWith("!"), "file pattern must be a positive relative glob");
            });
            requireLimit(limit);
        }
    }

    public record ReadSourceRequest(SourceContext context, String path, int startLine, int maxLines,
            Optional<String> cursor) {
        public ReadSourceRequest {
            context = Objects.requireNonNull(context, "source context");
            path = SourcePathPolicy.requireFile(path);
            ModelValidation.require(startLine >= 1, "start line must be positive");
            ModelValidation.require(maxLines >= 1 && maxLines <= MAX_READ_LINES,
                    "read line limit must be between 1 and 500");
            cursor = requireCursor(cursor);
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
            Optional<String> revision) {
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
            ModelValidation.require((sourceStatus == SourceStatus.READY) == revision.isPresent(),
                    "only ready repositories have a published revision");
        }
    }

    public record ContextResult(String repositoryId, String defaultBranch, SourceStatus sourceStatus,
            SemanticStatus semanticStatus, Optional<SourceContext> context, Optional<GuideInfo> projectGuide,
            Optional<Coverage> coverage) {
        public ContextResult {
            repositoryId = new RepositoryId(repositoryId).value();
            defaultBranch = ModelValidation.requiredText(defaultBranch, "default branch");
            sourceStatus = Objects.requireNonNull(sourceStatus, "source status");
            semanticStatus = Objects.requireNonNull(semanticStatus, "semantic status");
            context = Objects.requireNonNull(context, "source context");
            projectGuide = Objects.requireNonNull(projectGuide, "project guide");
            coverage = Objects.requireNonNull(coverage, "source coverage");
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

    public record FileEntry(String path, EntryKind kind, Optional<EntryStatus> status, Optional<Long> byteLength,
            boolean navigationHint) {
        public FileEntry {
            path = SourcePathPolicy.requireFile(path);
            kind = Objects.requireNonNull(kind, "entry kind");
            status = Objects.requireNonNull(status, "entry status");
            byteLength = Objects.requireNonNull(byteLength, "byte length");
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

    public record SourceResult(SourceContext context, String path, int startLine, Optional<Integer> endLine,
            String content, boolean hasMore, Optional<String> nextCursor, boolean startsMidLine,
            boolean endsMidLine, boolean navigationHint) {
        public SourceResult {
            context = Objects.requireNonNull(context, "source context");
            path = SourcePathPolicy.requireFile(path);
            ModelValidation.require(startLine >= 1, "start line must be positive");
            endLine = Objects.requireNonNull(endLine, "end line");
            if (endLine.isPresent()) {
                ModelValidation.require(endLine.orElseThrow() >= startLine, "source range must not run backwards");
            }
            content = Objects.requireNonNull(content, "source content");
            ModelValidation.require(content.isEmpty() == endLine.isEmpty(),
                    "empty source content must not fabricate a line range");
            nextCursor = requireCursor(nextCursor);
            ModelValidation.require(hasMore == nextCursor.isPresent(), "continuation requires a next cursor");
            ModelValidation.require(!content.isEmpty() || !startsMidLine && !endsMidLine,
                    "empty content cannot be a partial line");
        }
    }

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
    public enum SemanticStatus { NOT_READY }
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
