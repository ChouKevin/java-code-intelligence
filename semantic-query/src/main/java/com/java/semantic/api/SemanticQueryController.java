package com.java.semantic.api;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** HTTP transport adapter for the approved Semantic query operations. */
@RestController
@RequestMapping("/api/v1")
public final class SemanticQueryController {
    private final SemanticQueryFacade facade;

    public SemanticQueryController(SemanticQueryFacade facade) {
        this.facade = Objects.requireNonNull(facade, "semantic query facade is required");
    }

    @GetMapping("/repositories")
    public SemanticQueryContract.RepositoryCollection listRepositories(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return facade.listRepositories(new SemanticQueryContract.PageRequest(offset, limit));
    }

    @GetMapping("/repositories/{repositoryId}")
    public SemanticQueryContract.RepositoryItem getRepository(@PathVariable String repositoryId) {
        return facade.getRepository(new SemanticQueryContract.RepositoryRequest(requiredText(repositoryId, "repositoryId")));
    }

    @PostMapping("/search-code")
    public SemanticQueryContract.SearchCodeResult searchCode(@RequestBody SearchCodeHttpRequest request) {
        return facade.searchCode(new SemanticQueryContract.SearchCodeRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.query(), "query"),
                Objects.requireNonNullElse(request.kinds(), Set.of()), optionalText(request.packagePrefix()),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/fact-source")
    public SemanticQueryContract.FactSourceResult factSource(@RequestBody FactSourceHttpRequest request) {
        return facade.getFactSource(new SemanticQueryContract.FactSourceRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.factId(), "factId"), contextLines(request.contextLines())));
    }

    @PostMapping("/entry-points")
    public SemanticQueryContract.CollectionResult entryPoints(@RequestBody EntryPointHttpRequest request) {
        return facade.listEntryPoints(new SemanticQueryContract.EntryPointRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), Objects.requireNonNullElse(request.kinds(), Set.of()),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/api-routes")
    public SemanticQueryContract.CollectionResult apiRoutes(@RequestBody ApiRouteHttpRequest request) {
        return facade.findApiRoutes(new SemanticQueryContract.ApiRouteRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), required(request.httpMethod(), "httpMethod"),
                requiredText(request.path(), "path"), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/event-listeners")
    public SemanticQueryContract.CollectionResult eventListeners(@RequestBody EventListenerHttpRequest request) {
        return facade.findEventListeners(new SemanticQueryContract.EventListenerRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.eventType(), "eventType"),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/type-members")
    public SemanticQueryContract.CollectionResult typeMembers(@RequestBody TypeMemberHttpRequest request) {
        return facade.listTypeMembers(new SemanticQueryContract.TypeMemberRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.typeFactId(), "typeFactId"),
                Objects.requireNonNullElse(request.kinds(), Set.of()), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/method-implementations")
    public SemanticQueryContract.CollectionResult methodImplementations(@RequestBody MethodRelationHttpRequest request) {
        return facade.findMethodImplementations(methodRelationRequest(request));
    }

    @PostMapping("/references")
    public SemanticQueryContract.CollectionResult references(@RequestBody RelationHttpRequest request) {
        return facade.findReferences(relationRequest(request));
    }

    @PostMapping("/callers")
    public SemanticQueryContract.CollectionResult callers(@RequestBody MethodRelationHttpRequest request) {
        return facade.findCallers(methodRelationRequest(request));
    }

    @PostMapping("/callees")
    public SemanticQueryContract.CollectionResult callees(@RequestBody MethodRelationHttpRequest request) {
        return facade.findCallees(methodRelationRequest(request));
    }

    @PostMapping("/git/branches")
    public SemanticQueryContract.GitBranchCollection gitBranches(@RequestBody GitBranchHttpRequest request) {
        return facade.listGitBranches(new SemanticQueryContract.GitBranchRequest(requiredText(request.repositoryId(), "repositoryId"),
                optionalText(request.catalogId()), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/git/commits")
    public SemanticQueryContract.GitCommitCollection gitCommits(@RequestBody GitCommitHttpRequest request) {
        return facade.listGitCommits(new SemanticQueryContract.GitCommitRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.historyId(), "historyId"), requiredText(request.revision(), "revision"),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/git/comparisons")
    public SemanticQueryContract.GitComparisonCollection gitComparisons(@RequestBody GitComparisonHttpRequest request) {
        return facade.compareRevisions(new SemanticQueryContract.GitComparisonRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.comparisonId(), "comparisonId"), requiredText(request.previous(), "previous"),
                requiredText(request.current(), "current"), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/git/file-diff")
    public SemanticQueryContract.GitFileDiffResult gitFileDiff(@RequestBody GitFileDiffHttpRequest request) {
        return facade.getFileDiff(new SemanticQueryContract.GitFileDiffRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.comparisonId(), "comparisonId"), requiredText(request.previous(), "previous"),
                requiredText(request.current(), "current"), requiredText(request.changeId(), "changeId"), optionalText(request.cursor())));
    }

    @PostMapping("/git/files")
    public SemanticQueryContract.GitFileCollection gitFiles(@RequestBody GitFileListHttpRequest request) {
        return facade.listFiles(new SemanticQueryContract.GitFileListRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.snapshotId(), "snapshotId"), requiredText(request.revision(), "revision"),
                required(request.directory(), "directory"), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/git/file")
    public SemanticQueryContract.GitFileContent gitFile(@RequestBody GitFileReadHttpRequest request) {
        return facade.readFile(new SemanticQueryContract.GitFileReadRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.snapshotId(), "snapshotId"), requiredText(request.revision(), "revision"), requiredText(request.path(), "path"),
                Optional.ofNullable(request.startLine()), fileLines(request.maxLines()), optionalText(request.cursor())));
    }

    @PostMapping("/git/search")
    public SemanticQueryContract.GitTextSearchResult gitSearch(@RequestBody GitTextSearchHttpRequest request) {
        return facade.searchText(new SemanticQueryContract.GitTextSearchRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.snapshotId(), "snapshotId"), requiredText(request.revision(), "revision"), requiredString(request.query(), "query"),
                optionalText(request.directory()), optionalText(request.cursor()), limit(request.limit())));
    }

    private static SemanticQueryContract.RelationRequest methodRelationRequest(MethodRelationHttpRequest request) {
        return new SemanticQueryContract.RelationRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.methodFactId(), "methodFactId"),
                offset(request.offset()), limit(request.limit()));
    }

    private static SemanticQueryContract.RelationRequest relationRequest(RelationHttpRequest request) {
        return new SemanticQueryContract.RelationRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.revision(), "revision"), requiredText(request.factId(), "factId"),
                offset(request.offset()), limit(request.limit()));
    }

    private static String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requiredString(String value, String field) {
        if (Objects.isNull(value) || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        return Optional.ofNullable(value).orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private static Optional<String> optionalText(String value) {
        return Optional.ofNullable(value);
    }

    private static int offset(Integer offset) {
        return Objects.requireNonNullElse(offset, 0);
    }

    private static int limit(Integer limit) {
        return Objects.requireNonNullElse(limit, SemanticQueryContract.DEFAULT_LIMIT);
    }

    private static int contextLines(Integer contextLines) {
        return Objects.requireNonNullElse(contextLines, 0);
    }

    private static int fileLines(Integer maxLines) {
        return Objects.requireNonNullElse(maxLines, SemanticQueryContract.DEFAULT_FILE_LINES);
    }

    record SearchCodeHttpRequest(String repositoryId, String revision, String query, Set<CodeFactKind> kinds,
                                 String packagePrefix, Integer offset, Integer limit) {
    }

    record FactSourceHttpRequest(String repositoryId, String revision, String factId, Integer contextLines) {
    }

    record EntryPointHttpRequest(String repositoryId, String revision, Set<EntryPointKind> kinds, Integer offset, Integer limit) {
    }

    record ApiRouteHttpRequest(String repositoryId, String revision, SemanticQueryContract.HttpMethod httpMethod, String path,
                               Integer offset, Integer limit) {
    }

    record EventListenerHttpRequest(String repositoryId, String revision, String eventType, Integer offset, Integer limit) {
    }

    record TypeMemberHttpRequest(String repositoryId, String revision, String typeFactId, Set<CodeFactKind> kinds,
                                 Integer offset, Integer limit) {
    }

    record MethodRelationHttpRequest(String repositoryId, String revision, String methodFactId, Integer offset, Integer limit) {
    }

    record RelationHttpRequest(String repositoryId, String revision, String factId, Integer offset, Integer limit) {
    }

    record GitBranchHttpRequest(String repositoryId, String catalogId, Integer offset, Integer limit) { }
    record GitCommitHttpRequest(String repositoryId, String historyId, String revision, Integer offset, Integer limit) { }
    record GitComparisonHttpRequest(String repositoryId, String comparisonId, String previous, String current, Integer offset, Integer limit) { }
    record GitFileDiffHttpRequest(String repositoryId, String comparisonId, String previous, String current, String changeId, String cursor) { }
    record GitFileListHttpRequest(String repositoryId, String snapshotId, String revision, String directory, Integer offset, Integer limit) { }
    record GitFileReadHttpRequest(String repositoryId, String snapshotId, String revision, String path, Integer startLine, Integer maxLines, String cursor) { }
    record GitTextSearchHttpRequest(String repositoryId, String snapshotId, String revision, String query, String directory, String cursor, Integer limit) { }
}
