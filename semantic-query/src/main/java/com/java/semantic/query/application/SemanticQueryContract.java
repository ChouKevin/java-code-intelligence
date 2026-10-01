package com.java.semantic.query.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.git.GitComparisonPolicyCoverage;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideProvenance;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceContentKind;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** One application contract for the read-only HTTP and MCP operations. */
public final class SemanticQueryContract {
    public static final int DEFAULT_LIMIT = 20;
    public static final int DEFAULT_COMMIT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CONTEXT_LINES = 20;
    public static final int DEFAULT_FILE_LINES = 200;
    public static final int MAX_FILE_LINES = 500;
    public static final int MAX_CONTENT_BYTES = 64 * 1024;
    public static final Set<String> OPERATIONS = Set.of("list_repositories", "get_context", "search_code",
            "list_files", "search_text", "read_source", "list_entry_points", "get_outline", "find_relations",
            "list_git_branches", "list_git_commits", "compare_revisions", "get_file_diff");
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");

    private SemanticQueryContract() { }

    public enum ContextKind { CURRENT, REVIEW }
    public enum SelectorKind { CURRENT, REVIEW, COMMIT, RANGE }
    public enum ContextState { UNINDEXED, NOT_PREPARED, PREPARING, FAILED, READY }
    public enum EndpointKind { EMPTY_TREE, REVISION }
    public enum SourceTargetKind { FACT, FILE }
    public enum OutlineTargetKind { TYPE, FILE }
    public enum EntryKind { HTTP, EVENT, MQ, SCHEDULE }
    public enum RelationMode { CALLERS, CALLEES, IMPLEMENTATIONS, REFERENCES }
    public enum TargetResolution { INTERNAL, EXTERNAL, UNRESOLVED }
    public enum FileEntryType { FILE, DIRECTORY }
    public enum CoverageScope { GENERATION, AUTHORIZED_SOURCE_FILES }
    public enum RelationEvidenceScope { PROJECTED_CALLS, PROJECTED_IMPLEMENTATIONS, PROJECTED_REFERENCES }
    public enum HttpMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, ALL }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ReadContext(ContextKind kind, String repositoryId, String revision,
            Optional<String> reviewId, Optional<ReviewSide> side) {
        public ReadContext {
            Objects.requireNonNull(kind, "context kind is required");
            repositoryId = repository(repositoryId);
            revision = SemanticQueryContract.revision(revision);
            reviewId = optional(reviewId).map(value -> new ReviewId(value).value());
            side = optional(side);
            boolean review = kind == ContextKind.REVIEW;
            ModelValidation.require(review ? reviewId.isPresent() && side.isPresent()
                    : reviewId.isEmpty() && side.isEmpty(), "context must be a strict CURRENT or REVIEW union");
        }
        public static ReadContext current(String repositoryId, String revision) {
            return new ReadContext(ContextKind.CURRENT, repositoryId, revision, Optional.empty(), Optional.empty());
        }
        public static ReadContext review(String repositoryId, String reviewId, ReviewSide side, String revision) {
            return new ReadContext(ContextKind.REVIEW, repositoryId, revision, Optional.of(reviewId), Optional.of(side));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ContextSelector(SelectorKind kind, Optional<String> reviewId, Optional<String> revision,
            Optional<String> beforeRevision, Optional<String> afterRevision) {
        public ContextSelector {
            Objects.requireNonNull(kind, "selector kind is required");
            reviewId = optional(reviewId).map(value -> new ReviewId(value).value());
            revision = optional(revision).map(SemanticQueryContract::revision);
            beforeRevision = optional(beforeRevision).map(SemanticQueryContract::revision);
            afterRevision = optional(afterRevision).map(SemanticQueryContract::revision);
            boolean valid = switch (kind) {
                case CURRENT -> reviewId.isEmpty() && revision.isEmpty() && beforeRevision.isEmpty() && afterRevision.isEmpty();
                case REVIEW -> reviewId.isPresent() && revision.isEmpty() && beforeRevision.isEmpty() && afterRevision.isEmpty();
                case COMMIT -> reviewId.isEmpty() && revision.isPresent() && beforeRevision.isEmpty() && afterRevision.isEmpty();
                case RANGE -> reviewId.isEmpty() && revision.isEmpty() && beforeRevision.isPresent() && afterRevision.isPresent();
            };
            ModelValidation.require(valid, "selector fields do not match its kind");
        }
        public Optional<ReviewSelection> requestedSelection() {
            return switch (kind) {
                case COMMIT -> Optional.of(ReviewSelection.commit(RepositoryRevision.ofSha(revision.orElseThrow())));
                case RANGE -> Optional.of(ReviewSelection.range(RepositoryRevision.ofSha(beforeRevision.orElseThrow()),
                        RepositoryRevision.ofSha(afterRevision.orElseThrow())));
                default -> Optional.empty();
            };
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ComparisonEndpoint(EndpointKind kind, Optional<String> revision) {
        public ComparisonEndpoint {
            Objects.requireNonNull(kind, "endpoint kind is required");
            revision = optional(revision).map(SemanticQueryContract::revision);
            ModelValidation.require((kind == EndpointKind.REVISION) == revision.isPresent(),
                    "only REVISION endpoints have a revision");
        }
    }

    public record ComparisonContext(String repositoryId, String reviewId, ComparisonEndpoint before, ComparisonEndpoint after) {
        public ComparisonContext {
            repositoryId = repository(repositoryId);
            reviewId = new ReviewId(reviewId).value();
            Objects.requireNonNull(before, "before endpoint is required");
            Objects.requireNonNull(after, "after endpoint is required");
            ModelValidation.require(after.kind() == EndpointKind.REVISION, "after endpoint must be a revision");
        }
    }

    public record PageRequest(Optional<String> cursor, int limit) {
        public PageRequest { cursor = optionalText(cursor, "cursor"); limit = SemanticQueryContract.limit(limit); }
    }
    public record RepositoryRequest(Optional<String> nameFilter, PageRequest page) {
        public RepositoryRequest {
            nameFilter = literal(nameFilter);
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record ContextRequest(String repositoryId, ContextSelector selector, int limit) {
        public ContextRequest {
            repositoryId = repository(repositoryId);
            Objects.requireNonNull(selector, "selector is required");
            limit = SemanticQueryContract.limit(limit);
        }
    }
    public record SearchCodeRequest(ReadContext context, String query, Set<CodeFactKind> kinds,
            Optional<String> packagePrefix, Optional<String> path, PageRequest page) {
        public SearchCodeRequest {
            Objects.requireNonNull(context, "context is required");
            query = ModelValidation.requiredText(query, "query");
            ModelValidation.require(query.length() >= 2 && query.length() <= 256, "query length must be between 2 and 256 characters");
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            packagePrefix = optionalText(packagePrefix, "package prefix").map(SemanticQueryContract::packagePrefix);
            path = optional(path).map(SemanticQueryContract::path);
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record FileListRequest(ReadContext context, String directory, Optional<String> nameFilter,
            Optional<String> pathFilter, PageRequest page) {
        public FileListRequest {
            Objects.requireNonNull(context, "context is required");
            directory = SemanticQueryContract.directory(directory);
            nameFilter = literal(nameFilter);
            pathFilter = literal(pathFilter);
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record TextSearchRequest(ReadContext context, String query, String directory, PageRequest page) {
        public TextSearchRequest {
            Objects.requireNonNull(context, "context is required");
            Objects.requireNonNull(query, "query is required");
            ModelValidation.require(!query.isEmpty() && query.indexOf('\n') < 0 && query.indexOf('\r') < 0
                    && query.codePointCount(0, query.length()) <= 256, "query must be one line of at most 256 code points");
            directory = SemanticQueryContract.directory(directory);
            Objects.requireNonNull(page, "page is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record SourceTarget(SourceTargetKind kind, Optional<String> factId, Optional<String> path,
            Optional<Integer> startLine, Optional<Integer> contextLines) {
        public SourceTarget {
            Objects.requireNonNull(kind, "source target kind is required");
            factId = optional(factId).map(value -> new CodeFactId(value).value());
            path = optional(path).map(SemanticQueryContract::path);
            startLine = optional(startLine);
            contextLines = optional(contextLines);
            if (kind == SourceTargetKind.FACT) {
                ModelValidation.require(factId.isPresent() && path.isEmpty() && startLine.isEmpty() && contextLines.isPresent(),
                        "FACT requires factId/contextLines and forbids file fields");
                ModelValidation.require(contextLines.orElseThrow() >= 0 && contextLines.orElseThrow() <= MAX_CONTEXT_LINES,
                        "context lines must be between 0 and 20");
            } else {
                ModelValidation.require(factId.isEmpty() && path.isPresent() && startLine.isPresent() && contextLines.isEmpty(),
                        "FILE requires path/startLine and forbids fact fields");
                ModelValidation.require(startLine.orElseThrow() >= 1, "start line must be positive");
            }
        }
    }
    public record SourceRequest(ReadContext context, SourceTarget target, int maxLines, Optional<String> cursor) {
        public SourceRequest {
            Objects.requireNonNull(context, "context is required");
            Objects.requireNonNull(target, "source target is required");
            ModelValidation.require(maxLines >= 1 && maxLines <= MAX_FILE_LINES, "max lines must be between 1 and 500");
            cursor = optionalText(cursor, "cursor");
        }
    }

    public record EntryPointRequest(ReadContext context, Optional<EntryKind> kind, Optional<String> handlerName,
            Optional<String> packagePrefix, Optional<HttpMethod> httpMethod, Optional<String> path,
            Optional<String> eventType, Optional<ExternalTarget.Destination> destination,
            Optional<String> trigger, PageRequest page) {
        public EntryPointRequest {
            Objects.requireNonNull(context, "context is required");
            kind = optional(kind);
            handlerName = optionalText(handlerName, "handler name");
            packagePrefix = optionalText(packagePrefix, "package prefix").map(SemanticQueryContract::packagePrefix);
            httpMethod = optional(httpMethod);
            path = optionalText(path, "HTTP path");
            path.ifPresent(value -> ModelValidation.require(value.startsWith("/")
                    && value.codePoints().noneMatch(Character::isWhitespace), "HTTP path must be absolute and contain no whitespace"));
            eventType = optionalText(eventType, "event type");
            destination = optional(destination);
            trigger = optionalText(trigger, "schedule trigger");
            requireEntryKind(kind, EntryKind.HTTP, httpMethod.isPresent() || path.isPresent());
            requireEntryKind(kind, EntryKind.EVENT, eventType.isPresent());
            requireEntryKind(kind, EntryKind.MQ, destination.isPresent());
            requireEntryKind(kind, EntryKind.SCHEDULE, trigger.isPresent());
            Objects.requireNonNull(page, "page is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record OutlineTarget(OutlineTargetKind kind, Optional<String> factId, Optional<String> path) {
        public OutlineTarget {
            Objects.requireNonNull(kind, "outline target kind is required");
            factId = optional(factId).map(value -> new CodeFactId(value).value());
            path = optional(path).map(SemanticQueryContract::path);
            ModelValidation.require(kind == OutlineTargetKind.TYPE ? factId.isPresent() && path.isEmpty()
                    : factId.isEmpty() && path.isPresent(), "outline target must be a strict TYPE or FILE union");
        }
    }
    public record OutlineRequest(ReadContext context, OutlineTarget target, Set<CodeFactKind> kinds, PageRequest page) {
        public OutlineRequest {
            Objects.requireNonNull(context, "context is required");
            Objects.requireNonNull(target, "outline target is required");
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            ModelValidation.require(kinds.stream().allMatch(SemanticQueryContract::declarationKind), "outline kinds must be declarations");
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record RelationRequest(ReadContext context, RelationMode relation, String factId, PageRequest page) {
        public RelationRequest {
            Objects.requireNonNull(context, "context is required");
            Objects.requireNonNull(relation, "relation is required");
            factId = new CodeFactId(factId).value();
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record GitBranchRequest(String repositoryId, PageRequest page) {
        public GitBranchRequest { repositoryId = repository(repositoryId); Objects.requireNonNull(page, "page is required"); }
    }
    public record GitCommitRequest(String repositoryId, String branch, PageRequest page) {
        public GitCommitRequest {
            repositoryId = repository(repositoryId);
            branch = ModelValidation.requiredText(branch, "branch");
            Objects.requireNonNull(page, "page is required");
        }
    }
    public record ComparisonRequest(ComparisonContext comparisonContext, PageRequest page) {
        public ComparisonRequest { Objects.requireNonNull(comparisonContext, "comparison context is required"); Objects.requireNonNull(page, "page is required"); }
    }
    public record FileDiffRequest(ComparisonContext comparisonContext, String changeId, Optional<String> cursor) {
        public FileDiffRequest {
            Objects.requireNonNull(comparisonContext, "comparison context is required");
            changeId = ModelValidation.requiredText(changeId, "change id");
            cursor = optionalText(cursor, "cursor");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record Page(int returned, boolean hasMore, Optional<String> nextCursor) {
        public Page {
            ModelValidation.require(returned >= 0, "returned count cannot be negative");
            nextCursor = optionalText(nextCursor, "next cursor");
            ModelValidation.require(hasMore == nextCursor.isPresent(), "continuation and hasMore must agree");
        }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record RepositoryItem(String repositoryId, String displayName, String defaultBranch,
            boolean configured, Optional<String> publishedRevision) { }
    public record RepositoryCollection(List<RepositoryItem> items, Page page) {
        public RepositoryCollection { items = List.copyOf(items); Objects.requireNonNull(page, "page is required"); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record JobIdentity(String jobId, String operation, String phase, Optional<String> requestId) { }
    public sealed interface ContextResult permits CurrentContextResult, ReviewContextResult {
        String repositoryId();
        ContextSelector selector();
        ContextState state();
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record CurrentContextResult(String repositoryId, ContextSelector selector, ContextState state,
            Optional<String> configuredBranch, Optional<JobIdentity> activeJob, Optional<String> revision,
            Optional<Instant> indexedAt, Optional<String> preparationBranch, Optional<ReadContext> context,
            Optional<ContextOverview> overview, Optional<GuideInfo> projectGuide) implements ContextResult { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ReviewContextResult(String repositoryId, ContextSelector selector, ContextState state,
            Optional<String> reviewId, Optional<String> jobId, Optional<String> failureCategory,
            Optional<ReviewSideContext> before, Optional<ReviewSideContext> after,
            Optional<ComparisonContext> comparisonContext) implements ContextResult { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ReviewSideContext(EndpointKind kind, Optional<String> revision, Optional<ReadContext> context,
            Optional<ContextOverview> overview, Optional<GuideInfo> projectGuide) { }
    public record BoundedSummary<T>(List<T> items, boolean omitted) {
        public BoundedSummary { items = List.copyOf(items); }
    }
    public record ModuleSummary(String path, BoundedSummary<String> sourceRoots) { }
    public record PackageSummary(String name, long sourceCount) { }
    public record EntryKindCount(EntryKind kind, long count) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record Coverage(CoverageScope scope, long readableCode, Optional<Long> excludedOrUnsupported,
            long extractionIssues, Optional<Long> unresolvedSemanticEvidence, List<String> omittedMetrics) {
        public Coverage { omittedMetrics = List.copyOf(omittedMetrics); }
    }
    public record ContextOverview(BoundedSummary<ModuleSummary> modules, BoundedSummary<String> sourceRoots,
            BoundedSummary<PackageSummary> packages, BoundedSummary<EntryKindCount> entryPoints,
            List<EntryKind> uncountedEntryKinds, Coverage coverage) {
        public ContextOverview { uncountedEntryKinds = List.copyOf(uncountedEntryKinds); }
    }
    public record GuideProvenance(int formatVersion, int promptVersion, String repositoryId, String analyzedRevision,
            Instant generatedAt, ProjectGuideProvenance.SourceScope sourceScope) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record GuideInfo(ProjectGuideState state, Optional<String> reason, Optional<String> path,
            Optional<String> digest, Optional<String> importedRevision, Optional<GuideProvenance> provenance, String freshness) {
        public static GuideInfo from(ProjectGuideMembership guide) {
            return new GuideInfo(guide.state(), guide.state() == ProjectGuideState.INVALID
                    ? Optional.of("Guide validation failed.") : Optional.empty(), guide.path(), guide.digest(),
                    guide.importedRevision().map(RepositoryRevision::value), guide.provenance().map(value ->
                    new GuideProvenance(value.formatVersion(), value.promptVersion(), value.repositoryId().value(),
                            value.analyzedRevision().value(), value.generatedAt(), value.sourceScope())), guide.freshness());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record CompactFact(String factId, CodeFactKind kind, String displayName, Optional<String> signature,
            String path, SyntaxRange range, Optional<String> canonical, Optional<MapperStatementKind> mapperStatementKind) { }
    public record FactCollection(ReadContext context, List<CompactFact> items, Page page) {
        public FactCollection { items = List.copyOf(items); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record EntryTrigger(Optional<HttpMethod> httpMethod, Optional<String> path, Optional<String> eventType,
            Optional<ExternalTarget.Destination> destination, Optional<String> trigger) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record EntryPointItem(EntryKind kind, Optional<String> factId, CompactFact handler, EntryTrigger trigger) { }
    public record EntryPointCollection(ReadContext context, List<EntryPointItem> items, Page page) {
        public EntryPointCollection { items = List.copyOf(items); }
    }
    public record Occurrence(String factId, String path, SyntaxRange range) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ExternalTargetInfo(String kind, String displayName, Optional<String> canonical, Optional<Integer> arity) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record RelationTargetItem(TargetResolution resolution, Optional<CompactFact> fact, Optional<ExternalTargetInfo> external) { }
    public record RelationItem(RelationKind relationKind, CompactFact origin, RelationTargetItem target, Occurrence occurrence) { }
    public record RelationCollection(ReadContext context, RelationMode relation, List<RelationItem> items,
            Page page, RelationEvidenceScope evidenceScope) {
        public RelationCollection { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record FileItem(String path, FileEntryType entryType, Optional<SourceContentKind> contentKind,
            Optional<String> contentStatus, Optional<Long> byteLength, Optional<GuideInfo> projectGuide) { }
    public record FileCollection(ReadContext context, List<FileItem> items, Page page) {
        public FileCollection { items = List.copyOf(items); }
    }
    public record TextMatch(String path, SyntaxRange range, String snippet, boolean snippetTruncated) { }
    public record TextSearchResult(ReadContext context, List<TextMatch> items, Page page, boolean scanComplete) {
        public TextSearchResult { items = List.copyOf(items); }
    }
    /** An absent end is an open FILE window ending at EOF, not an unknown fact boundary. */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record SourceWindow(SyntaxPosition start, Optional<SyntaxPosition> end) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record SourceResult(ReadContext context, SourceTarget target, String path, SourceContentKind contentKind,
            String contentStatus, Optional<String> content, Optional<SyntaxRange> factRange, Optional<SourceWindow> window,
            Optional<SyntaxRange> pageRange, boolean rangeComplete, boolean startLineComplete, boolean endLineComplete,
            Optional<String> nextCursor, Optional<GuideInfo> projectGuide) { }

    public record MetadataIdentity(String jobId, String catalogId, String historyId, String branch,
            String headRevision, Instant observedAt) { }
    public record GitBranchItem(String branch, String headRevision) { }
    public record GitBranchCollection(String repositoryId, MetadataIdentity metadata, List<GitBranchItem> items, Page page) {
        public GitBranchCollection { items = List.copyOf(items); }
    }
    public record GitCommitItem(String revision, String shortRevision, List<String> parents, String subject, Instant committedAt) {
        public GitCommitItem { parents = List.copyOf(parents); }
    }
    public record GitCommitCollection(String repositoryId, MetadataIdentity metadata, List<GitCommitItem> items, Page page) {
        public GitCommitCollection { items = List.copyOf(items); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record ChangeEndpoint(String path, String mode, String blobId, SourceContentKind contentKind, Optional<GuideInfo> projectGuide) { }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record GitChangeItem(String changeId, String kind, Optional<ChangeEndpoint> before, Optional<ChangeEndpoint> after,
            String diffStatus) { }
    public record ComparisonResult(ComparisonContext comparisonContext, String ancestry, GitComparisonPolicyCoverage policyCoverage,
            List<GitChangeItem> items, Page page) {
        public ComparisonResult { policyCoverage = Objects.requireNonNull(policyCoverage); items = List.copyOf(items); }
    }
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record FileDiffResult(ComparisonContext comparisonContext, GitChangeItem change, Optional<String> patch,
            boolean complete, Optional<String> nextCursor) { }

    static String repository(String value) { return RepositoryId.of(value).value(); }
    static String revision(String value) { return RepositoryRevision.ofSha(value).value(); }
    static String path(String value) {
        ModelValidation.require(SourceEvidencePolicy.validPath(value) && !".".equals(value), "a normalized file path is required");
        return value;
    }
    static String directory(String value) {
        Objects.requireNonNull(value, "directory is required");
        if (value.isEmpty() || value.equals(".")) return "";
        return path(value);
    }
    static int limit(int value) {
        ModelValidation.require(value >= 1 && value <= MAX_LIMIT, "limit must be between 1 and 100");
        return value;
    }
    static boolean declarationKind(CodeFactKind kind) {
        return switch (kind) {
            case TYPE, METHOD, FIELD, ENUM_CONSTANT, RECORD_COMPONENT, MAPPER_STATEMENT -> true;
            default -> false;
        };
    }
    private static void requireEntryKind(Optional<EntryKind> actual, EntryKind required, boolean fieldsPresent) {
        ModelValidation.require(!fieldsPresent || actual.filter(value -> value == required).isPresent(),
                "entry filter is not applicable to the selected kind");
    }
    private static String packagePrefix(String value) {
        ModelValidation.require(PACKAGE_PREFIX.matcher(value).matches(), "package prefix must be a Java package prefix");
        return value;
    }
    private static <T> Optional<T> optional(Optional<T> value) { return Objects.requireNonNull(value, "optional value is required"); }
    private static Optional<String> optionalText(Optional<String> value, String name) {
        return optional(value).map(text -> ModelValidation.requiredText(text, name));
    }
    private static Optional<String> literal(Optional<String> value) {
        return optional(value).map(text -> {
            ModelValidation.require(!text.isEmpty() && text.indexOf('\u0000') < 0, "a nonempty path literal is required");
            return text;
        });
    }
}
