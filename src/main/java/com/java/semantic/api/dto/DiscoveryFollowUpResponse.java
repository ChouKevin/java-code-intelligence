package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.MapperFragmentIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.dto.identity.SourceSymbolContextPayload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.PositionPayload;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** operation 與 request 是 Agent 可直接提交的下一輪 HTTP 契約，不是建議文字 */
public record DiscoveryFollowUpResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String operation,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ApiResponse api,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) RequestResponse request) {

    public DiscoveryFollowUpResponse {
        api = Objects.requireNonNull(api, "api is required");
        request = Objects.requireNonNull(request, "request is required");
    }

    /** 後續動作的固定 HTTP method、path 與 operationId */
    public record ApiResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String method, @ApiMonitoringField(ApiMonitoringMode.VALUE) String path, @ApiMonitoringField(ApiMonitoringMode.VALUE) String operationId) {
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
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target) implements RequestResponse {

        public GetMethodSourceRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** outgoing 或 incoming call graph 後續動作的完整請求 */
    public record AnalyzeCallGraphRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int depth,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target) implements RequestResponse {

        public AnalyzeCallGraphRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 方法實作探索後續動作的完整請求 */
    public record DiscoverMethodImplementationsRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload declarationTarget) implements RequestResponse {

        public DiscoverMethodImplementationsRequestResponse {
            declarationTarget = Objects.requireNonNull(
                    declarationTarget, "declarationTarget is required");
        }
    }

    /** 精確 typed concept resolve follow-up 的完整請求 */
    public record ResolveConceptRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptIdentityResponse identity) implements RequestResponse {

        public ResolveConceptRequestResponse {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 概念搜尋下一頁 follow-up 的單一搜尋條件 */
    public record ConceptSearchTermResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String value,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String matchMode) {
    }

    /** 宣告型概念導向型別成員探索的完整請求 */
    public record GetTypeMembersRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> memberKinds,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> namePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public GetTypeMembersRequestResponse {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }

    /** 型別成員下一頁後續動作的完整請求 */
    public record DiscoverTypeMembersRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> memberKinds,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> namePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverTypeMembersRequestResponse {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }

    /** 直接重送固定搜尋條件的概念下一頁完整 HTTP request */
    public record DiscoverConceptsRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) List<ConceptSearchTermResponse> terms,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> kinds,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String operator,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> packagePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverConceptsRequestResponse {
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            packagePrefix = Objects.requireNonNull(packagePrefix, "packagePrefix is required");
        }
    }

    /** 直接重送固定 event type 與頁碼的事件監聽器下一頁完整 HTTP request */
    public record DiscoverEventListenersRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String eventType,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {
    }

    /** RESOLVE_SOURCE_SYMBOL 後續動作的完整請求 */
    public record ResolveSourceSymbolRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceSymbolContextPayload context,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String symbol,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<PositionPayload> position)
            implements RequestResponse {

        public ResolveSourceSymbolRequestResponse {
            context = Objects.requireNonNull(context, "context is required");
            position = Objects.requireNonNull(position, "position is required");
        }
    }

    /** 內部 reference 下一頁的完整 exact target 請求 */
    public record FindInternalReferencesRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) InternalSourceReferenceTargetPayload target,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {
    }

    /** bounded source segment 的完整 exact range 請求 */
    public record GetSourceSegmentRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangePayload location,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int contextLines) implements RequestResponse {
    }

    /** evidence-source follow-up 的完整封閉 typed request */
    public record GetEvidenceSourceRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) EvidenceSourceIdentityPayload identity)
            implements RequestResponse {

        public GetEvidenceSourceRequestResponse {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }
}
