package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SourceRange;

import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.ConceptSearchTerm;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;

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
        RESOLVE_CONCEPT(
                "/v1/discovery/concepts/resolve",
                "resolveConcept",
                ResolveConceptRequest.class),
        GET_TYPE_MEMBERS(
                "/v1/discovery/type-members",
                "discoverTypeMembers",
                GetTypeMembersRequest.class),
        DISCOVER_TYPE_MEMBERS(
                "/v1/discovery/type-members",
                "discoverTypeMembers",
                TypeMembersRequest.class),
        DISCOVER_CONCEPTS(
                "/v1/discovery/concepts",
                "discoverConcepts",
                DiscoverConceptsRequest.class),
        DISCOVER_EVENT_LISTENERS(
                "/v1/discovery/event-listeners",
                "discoverEventListeners",
                DiscoverEventListenersRequest.class),
        RESOLVE_SOURCE_SYMBOL(
                "/v1/discovery/source-symbols/resolve",
                "resolveSourceSymbol",
                ResolveSourceSymbolRequest.class),
        FIND_INTERNAL_REFERENCES(
                "/v1/discovery/internal-references",
                "findInternalReferences",
                FindInternalReferencesRequest.class),
        GET_SOURCE_SEGMENT(
                "/v1/discovery/source-segment",
                "getSourceSegment",
                GetSourceSegmentRequest.class),
        GET_EVIDENCE_SOURCE(
                "/v1/discovery/evidence-source",
                "getEvidenceSource",
                GetEvidenceSourceRequest.class);

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
            AnalyzeCallGraphRequest,
            DiscoverMethodImplementationsRequest,
            ResolveConceptRequest,
            GetTypeMembersRequest,
            TypeMembersRequest,
            DiscoverConceptsRequest,
            DiscoverEventListenersRequest,
            ResolveSourceSymbolRequest,
            FindInternalReferencesRequest,
            GetSourceSegmentRequest,
            GetEvidenceSourceRequest {
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

    /** 精確 typed concept resolve endpoint 的完整請求 */
    public record ResolveConceptRequest(
            String repoId,
            String expectedRevision,
            ConceptIdentity identity) implements RequestProjection {

        public ResolveConceptRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 宣告型概念導向完整型別成員探索的安全請求 */
    public record GetTypeMembersRequest(
            String repoId,
            String expectedRevision,
            SourceTypeIdentity sourceType,
            List<TypeMemberKind> memberKinds,
            Optional<String> namePrefix,
            int offset,
            int limit) implements RequestProjection {

        public GetTypeMembersRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
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

    /** type member 下一頁重複原始 identity、篩選與固定 revision 的完整請求 */
    public record TypeMembersRequest(
            String repoId,
            String expectedRevision,
            SourceTypeIdentity sourceType,
            List<TypeMemberKind> memberKinds,
            Optional<String> namePrefix,
            int offset,
            int limit) implements RequestProjection {

        public TypeMembersRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
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

    /** 保留結構化搜尋條件與固定 revision 的概念探索續頁請求 */
    public record DiscoverConceptsRequest(
            String repoId,
            String expectedRevision,
            List<ConceptSearchTerm> terms,
            List<String> kinds,
            String operator,
            Optional<String> packagePrefix,
            int offset,
            int limit) implements RequestProjection {

        public DiscoverConceptsRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            operator = requiredText(operator, "operator");
            packagePrefix = Objects.requireNonNull(packagePrefix, "packagePrefix is required");
            if (!"ALL".equals(operator)) {
                throw new IllegalArgumentException("concept search operator must be ALL");
            }
            if (terms.size() < 1 || kinds.size() < 1) {
                throw new IllegalArgumentException("concept search terms and kinds are required");
            }
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("concept search page is invalid");
            }
        }
    }

    /** 保留事件型別、固定 revision 與分頁參數的事件監聽器續頁請求 */
    public record DiscoverEventListenersRequest(
            String repoId,
            String expectedRevision,
            String eventType,
            int offset,
            int limit) implements RequestProjection {

        public DiscoverEventListenersRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            eventType = requiredText(eventType, "eventType");
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("event listener page is invalid");
            }
        }
    }

    /** source-symbol endpoint 可直接重送的完整 revision-bound request */
    public record ResolveSourceSymbolRequest(
            String repoId,
            String expectedRevision,
            SourceSymbolContext context,
            String symbol,
            Optional<SyntaxPosition> position) implements RequestProjection {

        public ResolveSourceSymbolRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            context = Objects.requireNonNull(context, "context is required");
            symbol = requiredText(symbol, "symbol");
            position = Objects.requireNonNull(position, "position is required");
        }
    }

    /** 內部 reference 下一頁保留 exact target 與 revision 的完整請求 */
    public record FindInternalReferencesRequest(
            String repoId,
            String expectedRevision,
            ExactSourceDeclarationTarget target,
            int offset,
            int limit) implements RequestProjection {

        public FindInternalReferencesRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            target = Objects.requireNonNull(target, "target is required");
            if (offset < 0 || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("internal reference page is invalid");
            }
        }
    }

    /** exact source range 與 bounded context 的完整續讀請求 */
    public record GetSourceSegmentRequest(
            String repoId,
            String expectedRevision,
            SourceRange location,
            int contextLines) implements RequestProjection {

        public GetSourceSegmentRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            location = Objects.requireNonNull(location, "location is required");
            if (contextLines < 0 || contextLines > 20) {
                throw new IllegalArgumentException("contextLines must be between 0 and 20");
            }
        }
    }

    /** typed evidence source endpoint 的完整既有 identity 請求 */
    public record GetEvidenceSourceRequest(
            String repoId,
            String expectedRevision,
            EvidenceSourceQuery.EvidenceIdentity identity) implements RequestProjection {

        public GetEvidenceSourceRequest {
            repoId = requiredText(repoId, "repoId");
            expectedRevision = requiredText(expectedRevision, "expectedRevision");
            identity = Objects.requireNonNull(identity, "identity is required");
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
