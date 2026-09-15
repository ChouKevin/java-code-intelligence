package com.java.semantic.query.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SemanticQueryContract {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CONTEXT_LINES = 20;
    public static final int DEFAULT_FILE_LINES = 200;
    public static final int MAX_FILE_LINES = 500;

    public record PageRequest(int offset, int limit) {
        public PageRequest {
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record RepositoryRequest(String repositoryId) {
        public RepositoryRequest {
            repositoryId = requireRepositoryId(repositoryId);
        }
    }

    public record SearchCodeRequest(String repositoryId, String revision, String query,
                                    Set<CodeFactKind> kinds, Optional<String> packagePrefix, int offset, int limit) {
        public SearchCodeRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            query = ModelValidation.requiredText(query, "query");
            kinds = requireKindSet(kinds);
            packagePrefix = Objects.requireNonNull(packagePrefix, "package prefix is required")
                    .map(value -> ModelValidation.requiredText(value, "package prefix"));
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record FactRequest(String repositoryId, String revision, String factId) {
        public FactRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            factId = requireFactId(factId);
        }
    }

    public record FactSourceRequest(String repositoryId, String revision, String factId, int contextLines) {
        public FactSourceRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            factId = requireFactId(factId);
            contextLines = requireContextLines(contextLines);
        }
    }

    public record EntryPointRequest(String repositoryId, String revision, Set<EntryPointKind> kinds,
                                    int offset, int limit) {
        public EntryPointRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            kinds = requireKindSet(kinds);
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record ApiRouteRequest(String repositoryId, String revision, HttpMethod httpMethod, String path,
                                  int offset, int limit) {
        public ApiRouteRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            httpMethod = Objects.requireNonNull(httpMethod, "HTTP method is required");
            path = ModelValidation.requiredText(path, "path");
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record EventListenerRequest(String repositoryId, String revision, String eventType,
                                       int offset, int limit) {
        public EventListenerRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            eventType = ModelValidation.requiredText(eventType, "event type");
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record TypeMemberRequest(String repositoryId, String revision, String typeFactId,
                                    Set<CodeFactKind> kinds, int offset, int limit) {
        public TypeMemberRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            typeFactId = requireFactId(typeFactId);
            kinds = requireKindSet(kinds);
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record RelationRequest(String repositoryId, String revision, String factId, int offset, int limit) {
        public RelationRequest {
            repositoryId = requireRepositoryId(repositoryId);
            revision = requireRevision(revision);
            factId = requireFactId(factId);
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record GitBranchRequest(String repositoryId, Optional<String> catalogId, int offset, int limit) {
        public GitBranchRequest {
            repositoryId = requireRepositoryId(repositoryId);
            catalogId = Objects.requireNonNull(catalogId, "catalog id is required").map(value -> ModelValidation.requiredText(value, "catalog id"));
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record GitCommitRequest(String repositoryId, String historyId, String revision, int offset, int limit) {
        public GitCommitRequest {
            repositoryId = requireRepositoryId(repositoryId);
            historyId = ModelValidation.requiredText(historyId, "history id");
            revision = requireRevision(revision);
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record GitComparisonRequest(String repositoryId, String comparisonId, String previous, String current, int offset, int limit) {
        public GitComparisonRequest {
            repositoryId = requireRepositoryId(repositoryId);
            comparisonId = ModelValidation.requiredText(comparisonId, "comparison id");
            previous = requireRevision(previous);
            current = requireRevision(current);
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record GitFileDiffRequest(String repositoryId, String comparisonId, String previous, String current, String changeId,
                                     Optional<String> cursor) {
        public GitFileDiffRequest {
            repositoryId = requireRepositoryId(repositoryId);
            comparisonId = ModelValidation.requiredText(comparisonId, "comparison id");
            previous = requireRevision(previous);
            current = requireRevision(current);
            changeId = ModelValidation.requiredText(changeId, "change id");
            cursor = Objects.requireNonNull(cursor, "cursor is required");
        }
    }

    public record GitFileListRequest(String repositoryId, String snapshotId, String revision, String directory, int offset, int limit) {
        public GitFileListRequest {
            repositoryId = requireRepositoryId(repositoryId);
            snapshotId = ModelValidation.requiredText(snapshotId, "snapshot id");
            revision = requireRevision(revision);
            directory = Objects.requireNonNull(directory, "directory is required");
            offset = requireOffset(offset);
            limit = requireLimit(limit);
        }
    }

    public record GitFileReadRequest(String repositoryId, String snapshotId, String revision, String path, Optional<Integer> startLine,
                                     int maxLines, Optional<String> cursor) {
        public GitFileReadRequest {
            repositoryId = requireRepositoryId(repositoryId);
            snapshotId = ModelValidation.requiredText(snapshotId, "snapshot id");
            revision = requireRevision(revision);
            path = ModelValidation.requiredText(path, "path");
            startLine = Objects.requireNonNull(startLine, "start line is required");
            if (startLine.isPresent() && startLine.get() < 1) {
                throw new IllegalArgumentException("start line must be positive");
            }
            maxLines = requireFileLines(maxLines);
            cursor = Objects.requireNonNull(cursor, "cursor is required");
            if (cursor.isPresent() && startLine.isPresent()) {
                throw new IllegalArgumentException("read cursor cannot be combined with start line");
            }
        }
    }

    public record GitTextSearchRequest(String repositoryId, String snapshotId, String revision, String query, Optional<String> directory,
                                       Optional<String> cursor, int limit) {
        public GitTextSearchRequest {
            repositoryId = requireRepositoryId(repositoryId);
            snapshotId = ModelValidation.requiredText(snapshotId, "snapshot id");
            revision = requireRevision(revision);
            query = requireTextQuery(query);
            directory = Objects.requireNonNull(directory, "directory is required");
            cursor = Objects.requireNonNull(cursor, "cursor is required");
            limit = requireLimit(limit);
        }
    }

    public enum HttpMethod {
        GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, ALL
    }

    public record RepositoryItem(String repositoryId, String revision) {
    }

    public record RepositoryCollection(List<RepositoryItem> items, Page page) {
        public RepositoryCollection {
            items = List.copyOf(Objects.requireNonNull(items, "repository items are required"));
            page = Objects.requireNonNull(page, "page is required");
        }
    }

    public record Page(int offset, int limit, int returned, long total, boolean hasMore) {
    }

    public record GitBranchItem(String branch, String head) { }
    public record GitBranchCollection(String repositoryId, String catalogId, java.time.Instant observedAt,
                                      List<GitBranchItem> items, Page page) {
        public GitBranchCollection { items = List.copyOf(Objects.requireNonNull(items, "git branch items are required")); }
    }
    public record GitCommitItem(String revision, List<String> parents, String subject, java.time.Instant committedAt) {
        public GitCommitItem { parents = List.copyOf(Objects.requireNonNull(parents, "git commit parents are required")); }
    }
    public record GitCommitCollection(String repositoryId, String historyId, String revision, java.time.Instant preparedAt,
                                      List<GitCommitItem> items, Page page) {
        public GitCommitCollection { items = List.copyOf(Objects.requireNonNull(items, "git commit items are required")); }
    }
    public record GitChangeItem(String changeId, String kind, String oldPath, String newPath, String oldMode, String newMode,
                                String oldBlobId, String newBlobId, String diffStatus) { }
    public record GitComparisonCollection(String repositoryId, String comparisonId, String previous, String current,
                                          String previousSnapshotId, String currentSnapshotId, String ancestry,
                                          List<GitChangeItem> items, Page page) {
        public GitComparisonCollection { items = List.copyOf(Objects.requireNonNull(items, "git comparison items are required")); }
    }
    public record GitFileDiffResult(String repositoryId, String comparisonId, String previous, String current,
                                    GitChangeItem change, String patch,
                                    @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> nextCursor) {
        public GitFileDiffResult { nextCursor = Objects.requireNonNull(nextCursor, "next cursor is required"); }
    }
    public record GitSnapshotCoverage(long inventoryCount, long readableTextCount, long binaryCount, long unsupportedEncodingCount,
                                      long tooLargeCount, long symlinkCount, long submoduleCount, long lfsPointerCount,
                                      long unsupportedPathCount) { }
    public record GitFileItem(String path, String pathKey, String entryType, long byteLength, String contentStatus) { }
    public record GitFileCollection(String repositoryId, String snapshotId, String revision, List<GitFileItem> items, Page page,
                                    GitSnapshotCoverage coverage) {
        public GitFileCollection { items = List.copyOf(Objects.requireNonNull(items, "git file items are required")); }
    }
    public record GitFileContent(String repositoryId, String snapshotId, String revision, String path, String pathKey,
                                 String contentStatus, String content, int startLine, int endLine, boolean startLineComplete,
                                 boolean endLineComplete, @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> nextCursor) {
        public GitFileContent { nextCursor = Objects.requireNonNull(nextCursor, "next cursor is required"); }
    }
    public record GitTextMatch(String path, String pathKey, int line, int column, String snippet, boolean snippetTruncated) { }
    public record GitTextSearchResult(String repositoryId, String snapshotId, String revision, List<GitTextMatch> items,
                                      boolean scanComplete, @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> nextCursor,
                                      GitSnapshotCoverage coverage) {
        public GitTextSearchResult { items = List.copyOf(Objects.requireNonNull(items, "git text matches are required")); nextCursor = Objects.requireNonNull(nextCursor, "next cursor is required"); }
    }

    public record SourceSnippet(String path, int startLine, int endLine, String code) {
    }

    public record RelationSite(String factId, SourceSnippet source) {
    }

    public record FactRange(int startLine, int endLine) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProgramElement(String factId, CodeFactKind kind, String displayName, SourceSnippet source) {
    }

    public record Trigger(String kind, String method, String value) {
    }

    public record EntryPointItem(String factId, ProgramElement handler, Trigger trigger) {
    }

    public record EventListenerItem(String eventType, ProgramElement handler) {
    }

    public record CallerItem(ProgramElement caller, RelationSite callSite) {
    }

    public enum CalleeResolutionStatus {
        INDEXED, UNINDEXED_TARGET, UNRESOLVED
    }

    public record CalleeItem(ProgramElement callee, RelationSite callSite, CalleeResolutionStatus resolutionStatus) {
    }

    public record ReferenceItem(ProgramElement container, RelationSite referenceSite) {
    }

    public record ImplementationItem(ProgramElement implementation, RelationKind relationKind) {
    }

    public record CollectionResult(String repositoryId, String revision, List<?> items, Page page) {
    }

    /** Compact extraction status for the source rows already authorized for a code search. */
    public record SourceCoverage(long indexedSourceCount, int issueCount, List<String> issueCodes) {
        public SourceCoverage {
            issueCodes = List.copyOf(Objects.requireNonNull(issueCodes, "source issue codes are required"));
        }
    }

    public record SearchCodeResult(String repositoryId, String revision, List<ProgramElement> items, Page page,
                                   SourceCoverage sourceCoverage) {
        public SearchCodeResult {
            items = List.copyOf(Objects.requireNonNull(items, "search items are required"));
            page = Objects.requireNonNull(page, "page is required");
            sourceCoverage = Objects.requireNonNull(sourceCoverage, "source coverage is required");
        }
    }

    public record FactSourceResult(String repositoryId, String revision, String factId,
                                   SourceSnippet source, FactRange factRange) {
    }

    private SemanticQueryContract() {
    }

    private static String requireRepositoryId(String repositoryId) {
        return new RepositoryId(repositoryId).value();
    }

    private static String requireRevision(String revision) {
        return new RepositoryRevision(revision).value();
    }

    private static String requireFactId(String factId) {
        return new CodeFactId(factId).value();
    }

    private static int requireOffset(int offset) {
        ModelValidation.require(offset >= 0, "offset must not be negative");
        return offset;
    }

    private static int requireLimit(int limit) {
        ModelValidation.require(limit >= 1 && limit <= MAX_LIMIT,
                "limit must be between 1 and " + MAX_LIMIT);
        return limit;
    }

    private static int requireFileLines(int maxLines) {
        if (maxLines < 1 || maxLines > MAX_FILE_LINES) {
            throw new IllegalArgumentException("max lines must be between 1 and " + MAX_FILE_LINES);
        }
        return maxLines;
    }

    private static String requireTextQuery(String query) {
        if (Objects.isNull(query)) {
            throw new IllegalArgumentException("query is required");
        }
        if (query.isEmpty() || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0 || query.codePointCount(0, query.length()) > 256) {
            throw new IllegalArgumentException("query must be one single line of at most 256 Unicode code points");
        }
        return query;
    }

    private static int requireContextLines(int contextLines) {
        ModelValidation.require(contextLines >= 0 && contextLines <= MAX_CONTEXT_LINES,
                "context lines must be between 0 and " + MAX_CONTEXT_LINES);
        return contextLines;
    }

    private static <T> Set<T> requireKindSet(Set<T> kinds) {
        Set<T> requiredKinds = Objects.requireNonNull(kinds, "kinds are required");
        if (requiredKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("kinds must not contain null values");
        }
        return Set.copyOf(requiredKinds);
    }
}
