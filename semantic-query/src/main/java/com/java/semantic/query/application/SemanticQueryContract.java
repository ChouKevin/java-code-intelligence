package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
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
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
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
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
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
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
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

    public record SourceSnippet(String path, int startLine, int endLine, String code) {
    }

    public record FactRange(int startLine, int endLine) {
    }

    public record ProgramElement(String factId, CodeFactKind kind, String displayName, SourceSnippet source) {
    }

    public record CollectionResult(String repositoryId, String revision, List<?> items, Page page) {
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

    private static int requireContextLines(int contextLines) {
        ModelValidation.require(contextLines >= 0 && contextLines <= MAX_CONTEXT_LINES,
                "context lines must be between 0 and " + MAX_CONTEXT_LINES);
        return contextLines;
    }
}
