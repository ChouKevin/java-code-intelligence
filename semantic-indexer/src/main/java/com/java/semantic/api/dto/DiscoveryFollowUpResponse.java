package com.java.semantic.api.dto;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.MapperFragmentIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.dto.identity.SourceSymbolContextPayload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** operation 與 request 是 Agent 可直接提交的下一輪 HTTP 契約，不是建議文字 */
public record DiscoveryFollowUpResponse(
        @MonitoringField(MonitoringMode.VALUE) String operation,
        @MonitoringField(MonitoringMode.NESTED) ApiResponse api,
        @MonitoringField(MonitoringMode.NESTED) RequestResponse request) {

    public DiscoveryFollowUpResponse {
        api = Objects.requireNonNull(api, "api is required");
        request = Objects.requireNonNull(request, "request is required");
    }

    /** 後續動作的固定 HTTP method、path 與 operationId */
    public record ApiResponse(@MonitoringField(MonitoringMode.VALUE) String method, @MonitoringField(MonitoringMode.VALUE) String path, @MonitoringField(MonitoringMode.VALUE) String operationId) {
    }

    /** 後續動作可攜帶的封閉完整請求投影 */
    public sealed interface RequestResponse permits
            GetMethodSourceRequestResponse,
            AnalyzeCallGraphRequestResponse,
            DiscoverMethodImplementationsRequestResponse,
            ResolveConceptRequestResponse,
            GetTypeMembersRequestResponse,
            DiscoverTypeMembersRequestResponse,
            DiscoverConceptsRequestResponse,
            DiscoverEventListenersRequestResponse,
            ResolveSourceSymbolRequestResponse,
            FindInternalReferencesRequestResponse,
            GetSourceSegmentRequestResponse,
            GetEvidenceSourceRequestResponse {
    }

    /** exact method source 後續動作的完整請求 */
    public record GetMethodSourceRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target) implements RequestResponse {

        public GetMethodSourceRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** outgoing 或 incoming call graph 後續動作的完整請求 */
    public record AnalyzeCallGraphRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) int depth,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target) implements RequestResponse {

        public AnalyzeCallGraphRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 方法實作探索後續動作的完整請求 */
    public record DiscoverMethodImplementationsRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload declarationTarget) implements RequestResponse {

        public DiscoverMethodImplementationsRequestResponse {
            declarationTarget = Objects.requireNonNull(
                    declarationTarget, "declarationTarget is required");
        }
    }

    /** 精確 typed concept resolve follow-up 的完整請求 */
    public record ResolveConceptRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) ConceptIdentityResponse identity) implements RequestResponse {

        public ResolveConceptRequestResponse {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 概念搜尋下一頁 follow-up 的單一搜尋條件 */
    public record ConceptSearchTermResponse(
            @MonitoringField(MonitoringMode.VALUE) String value,
            @MonitoringField(MonitoringMode.VALUE) String matchMode) {
    }

    /** 宣告型概念導向型別成員探索的完整請求 */
    public record GetTypeMembersRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
            @MonitoringField(MonitoringMode.SIZE) List<String> memberKinds,
            @MonitoringField(MonitoringMode.OMIT) Optional<String> namePrefix,
            @MonitoringField(MonitoringMode.VALUE) int offset,
            @MonitoringField(MonitoringMode.VALUE) int limit) implements RequestResponse {

        public GetTypeMembersRequestResponse {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }

    /** 型別成員下一頁後續動作的完整請求 */
    public record DiscoverTypeMembersRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
            @MonitoringField(MonitoringMode.SIZE) List<String> memberKinds,
            @MonitoringField(MonitoringMode.OMIT) Optional<String> namePrefix,
            @MonitoringField(MonitoringMode.VALUE) int offset,
            @MonitoringField(MonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverTypeMembersRequestResponse {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }

    /** 直接重送固定搜尋條件的概念下一頁完整 HTTP request */
    public record DiscoverConceptsRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) List<ConceptSearchTermResponse> terms,
            @MonitoringField(MonitoringMode.SIZE) List<String> kinds,
            @MonitoringField(MonitoringMode.VALUE) String operator,
            @MonitoringField(MonitoringMode.VALUE) Optional<String> packagePrefix,
            @MonitoringField(MonitoringMode.VALUE) int offset,
            @MonitoringField(MonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverConceptsRequestResponse {
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            packagePrefix = Objects.requireNonNull(packagePrefix, "packagePrefix is required");
        }
    }

    /** 直接重送固定 event type 與頁碼的事件監聽器下一頁完整 HTTP request */
    public record DiscoverEventListenersRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.VALUE) String eventType,
            @MonitoringField(MonitoringMode.VALUE) int offset,
            @MonitoringField(MonitoringMode.VALUE) int limit) implements RequestResponse {
    }

    /** RESOLVE_SOURCE_SYMBOL 後續動作的完整請求 */
    public record ResolveSourceSymbolRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) SourceSymbolContextPayload context,
            @MonitoringField(MonitoringMode.VALUE) String symbol,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @MonitoringField(MonitoringMode.NESTED) Optional<PositionPayload> position)
            implements RequestResponse {

        public ResolveSourceSymbolRequestResponse {
            context = Objects.requireNonNull(context, "context is required");
            position = Objects.requireNonNull(position, "position is required");
        }
    }

    /** 內部 reference 下一頁的完整 exact target 請求 */
    public record FindInternalReferencesRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) InternalSourceReferenceTargetPayload target,
            @MonitoringField(MonitoringMode.VALUE) int offset,
            @MonitoringField(MonitoringMode.VALUE) int limit) implements RequestResponse {
    }

    /** bounded source segment 的完整 exact range 請求 */
    public record GetSourceSegmentRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) SourceRangePayload location,
            @MonitoringField(MonitoringMode.VALUE) int contextLines) implements RequestResponse {
    }

    /** evidence-source follow-up 的完整封閉 typed request */
    public record GetEvidenceSourceRequestResponse(
            @MonitoringField(MonitoringMode.VALUE) String repoId,
            @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) EvidenceSourceIdentityPayload identity)
            implements RequestResponse {

        public GetEvidenceSourceRequestResponse {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }
}
