package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.RepositoryRelativeSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 固定 operation、HTTP authority 與完整 typed request 的可執行後續動作 */
public record DiscoveryFollowUp(
        Operation operation,
        ApiProjection api,
        RequestProjection request) {

    public DiscoveryFollowUp {
        operation = Objects.requireNonNull(operation, "operation is required");
        api = Objects.requireNonNull(api, "api is required");
        request = Objects.requireNonNull(request, "request is required");
        if (!operation.api().equals(api)) {
            throw new IllegalArgumentException("api projection must match operation");
        }
        if (!operation.requestType().isInstance(request)) {
            throw new IllegalArgumentException("request projection must match operation");
        }
    }

    /** Agent 可直接執行且不需推導 endpoint 的固定操作 */
    public enum Operation {

        GET_METHOD_SOURCE(
                "/v1/discovery/method-source",
                "getMethodSource",
                GetMethodSourceRequest.class),
        GET_METHOD_SQL(
                "/v1/discovery/method-sql",
                "getMethodSql",
                GetMapperStatementRequest.class),
        ANALYZE_OUTGOING_CALL_GRAPH(
                "/v1/analyses/call-graphs/outgoing",
                "analyzeOutgoingCallGraph",
                AnalyzeCallGraphRequest.class),
        ANALYZE_INCOMING_CALL_GRAPH(
                "/v1/analyses/call-graphs/incoming",
                "analyzeIncomingCallGraph",
                AnalyzeCallGraphRequest.class),
        DISCOVER_METHOD_IMPLEMENTATIONS(
                "/v1/discovery/method-implementations",
                "discoverMethodImplementations",
                DiscoverMethodImplementationsRequest.class),
        DISCOVER_CONCEPTS(
                "/v1/discovery/concepts",
                "discoverConcepts",
                ConceptDiscoveryRequest.class),
        GET_TYPE_MEMBERS(
                "/v1/discovery/type-members",
                "discoverTypeMembers",
                GetTypeMembersRequest.class),
        GET_NEXT_PAGE(
                "/v1/discovery/type-members",
                "discoverTypeMembers",
                TypeMembersRequest.class);

        private final ApiProjection api;
        private final Class<? extends RequestProjection> requestType;

        Operation(
                String path,
                String operationId,
                Class<? extends RequestProjection> requestType) {
            this.api = new ApiProjection("POST", path, operationId);
            this.requestType = requestType;
        }

        /** 回傳固定 HTTP method、path 與 operationId */
        public ApiProjection api() {
            return api;
        }

        private Class<? extends RequestProjection> requestType() {
            return requestType;
        }
    }

    /** 可執行 HTTP endpoint 的固定 authority */
    public record ApiProjection(String method, String path, String operationId) {

        public ApiProjection {
            method = requiredText(method, "method");
            path = requiredText(path, "path");
            operationId = requiredText(operationId, "operationId");
        }
    }

    /** follow-up 可接受的封閉 HTTP request projection */
    public sealed interface RequestProjection permits
            GetMethodSourceRequest,
            GetMapperStatementRequest,
            AnalyzeCallGraphRequest,
            DiscoverMethodImplementationsRequest,
            ConceptDiscoveryRequest,
            GetTypeMembersRequest,
            TypeMembersRequest {
    }

    /** Phase 1 固定供 Phase 2 實作的 exact method source 完整請求 */
    public record GetMethodSourceRequest(
            String repoId,
            String expectedRevision,
            MethodTarget target) implements RequestProjection {

        public GetMethodSourceRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 已唯一解析 mapper statement 導向 exact SQL variants 的完整請求 */
    public record GetMapperStatementRequest(
            String repoId,
            String expectedRevision,
            MethodTarget target) implements RequestProjection {

        public GetMapperStatementRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** outgoing 與 incoming graph 共用的完整固定深度請求 */
    public record AnalyzeCallGraphRequest(
            String repoId,
            String expectedRevision,
            int depth,
            MethodTarget target) implements RequestProjection {

        public AnalyzeCallGraphRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            if (depth < 1 || depth > 2) {
                throw new IllegalArgumentException("depth must be between 1 and 2");
            }
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 方法實作探索 endpoint 的完整 declarationTarget 請求 */
    public record DiscoverMethodImplementationsRequest(
            String repoId,
            String expectedRevision,
            MethodTarget declarationTarget) implements RequestProjection {

        public DiscoverMethodImplementationsRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
        }
    }

    /** 概念搜尋 HTTP term 的固定 value 與 matchMode */
    public record ConceptTermRequest(String value, ConceptMatchMode matchMode) {

        public ConceptTermRequest {
            value = requiredText(value, "value");
            matchMode = Objects.requireNonNull(matchMode, "matchMode is required");
        }
    }

    /** 已解析欄位型別導向 TYPE canonical exact 搜尋的完整請求 */
    public record ConceptDiscoveryRequest(
            String repoId,
            String expectedRevision,
            String operator,
            List<ConceptTermRequest> terms,
            List<ConceptKind> kinds,
            Optional<String> packagePrefix,
            int offset,
            int limit) implements RequestProjection {

        public ConceptDiscoveryRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            if (!"ALL".equals(operator)) {
                throw new IllegalArgumentException("operator must be ALL");
            }
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            packagePrefix = Objects.requireNonNull(packagePrefix, "packagePrefix is required");
            if (terms.size() < 1 || terms.size() > 4 || kinds.size() < 1 || kinds.size() > 8) {
                throw new IllegalArgumentException("terms and kinds are required");
            }
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("concept page is invalid");
            }
        }

        /** 保留舊內部 follow-up 建構子；HTTP boundary 僅輸出單一 packagePrefix */
        public ConceptDiscoveryRequest(
                String repoId,
                String expectedRevision,
                List<ConceptTermRequest> terms,
                List<ConceptKind> kinds,
                List<String> packageFilters,
                int offset,
                int limit) {
            this(repoId, expectedRevision, "ALL", terms, kinds, optionalPackagePrefix(packageFilters), offset, limit);
        }

        private static Optional<String> optionalPackagePrefix(List<String> packageFilters) {
            List<String> filters = List.copyOf(Objects.requireNonNull(
                    packageFilters, "packageFilters are required"));
            if (filters.size() > 1) {
                throw new IllegalArgumentException("only one packagePrefix is supported");
            }
            return filters.stream().findFirst();
        }
    }

    /** 宣告型概念導向完整型別成員探索的安全請求 */
    public record GetTypeMembersRequest(
            String repoId,
            String expectedRevision,
            String sourceFile,
            String fullyQualifiedName,
            List<TypeMemberKind> memberKinds,
            Optional<String> namePrefix,
            int offset,
            int limit) implements RequestProjection {

        public GetTypeMembersRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
            fullyQualifiedName = requiredText(fullyQualifiedName, "fullyQualifiedName");
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
            if (memberKinds.size() < 1) {
                throw new IllegalArgumentException("memberKinds are required");
            }
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("type member page is invalid");
            }
        }
    }

    /** GET_NEXT_PAGE 重複原始型別 identity、篩選與固定 revision 的完整請求 */
    public record TypeMembersRequest(
            String repoId,
            String expectedRevision,
            String sourceFile,
            String fullyQualifiedName,
            List<TypeMemberKind> memberKinds,
            Optional<String> namePrefix,
            int offset,
            int limit) implements RequestProjection {

        public TypeMembersRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
            fullyQualifiedName = requiredText(fullyQualifiedName, "fullyQualifiedName");
            memberKinds = List.copyOf(Objects.requireNonNull(memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
            if (memberKinds.size() < 1) {
                throw new IllegalArgumentException("memberKinds are required");
            }
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("type member page is invalid");
            }
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
