package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 可直接執行且帶完整 HTTP authority 與請求投影的後續動作回應 */
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
            GetMapperStatementRequestResponse,
            GetMapperFragmentRequestResponse,
            GetMethodSourceSegmentRequestResponse,
            GetMapperStatementSegmentRequestResponse,
            GetMapperFragmentSegmentRequestResponse,
            AnalyzeCallGraphRequestResponse,
            DiscoverMethodImplementationsRequestResponse,
            DiscoverConceptsRequestResponse,
            GetTypeMembersRequestResponse,
            DiscoverTypeMembersRequestResponse {
    }

    /** exact method source 後續動作的完整請求 */
    public record GetMethodSourceRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target) implements RequestResponse {

        public GetMethodSourceRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 已唯一解析 mapper statement 導向 exact SQL variants 的完整請求 */
    public record GetMapperStatementRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target)
            implements RequestResponse {

        public GetMapperStatementRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /**
     * typed mapper evidence 提供完整 fragment identity 的 stateless exact XML 請求
     * 此請求不依賴 cache、session 或 operator 補填 authority
     */
    public record GetMapperFragmentRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED)
            MapperFragmentIdentityResponse fragmentIdentity) implements RequestResponse {

        public GetMapperFragmentRequestResponse {
            fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
        }
    }

    /**
     * 重送原始 method authority 的 stateless source segment 完整請求
     * contentRef 僅驗證內容一致性且不是 cache lookup key
     */
    public record GetMethodSourceSegmentRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) String contentRef,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentIndex) implements RequestResponse {

        public GetMethodSourceSegmentRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /**
     * 重送原始 mapper method authority 的 stateless statement segment 完整請求
     * contentRef 僅驗證內容一致性且不是 cache lookup key
     */
    public record GetMapperStatementSegmentRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) String contentRef,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentIndex) implements RequestResponse {

        public GetMapperStatementSegmentRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /**
     * 重送原始 fragment authority 的 stateless mapper XML segment 完整請求
     * contentRef 僅驗證內容一致性且不是 cache lookup key
     */
    public record GetMapperFragmentSegmentRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED)
            MapperFragmentIdentityResponse fragmentIdentity,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) String contentRef,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int segmentIndex) implements RequestResponse {

        public GetMapperFragmentSegmentRequestResponse {
            fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
        }
    }

    /** outgoing 或 incoming call graph 後續動作的完整請求 */
    public record AnalyzeCallGraphRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int depth,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target) implements RequestResponse {

        public AnalyzeCallGraphRequestResponse {
            target = Objects.requireNonNull(target, "target is required");
        }
    }

    /** 方法實作探索後續動作的完整請求 */
    public record DiscoverMethodImplementationsRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse declarationTarget) implements RequestResponse {

        public DiscoverMethodImplementationsRequestResponse {
            declarationTarget = Objects.requireNonNull(
                    declarationTarget, "declarationTarget is required");
        }
    }

    /** 概念探索 follow-up 的單一搜尋條件 */
    public record ConceptTermResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String value, @ApiMonitoringField(ApiMonitoringMode.VALUE) String matchMode) {
    }

    /** 概念探索或概念下一頁後續動作的完整請求 */
    public record DiscoverConceptsRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String operator,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ConceptTermResponse> terms,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> kinds,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> packagePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverConceptsRequestResponse {
            terms = List.copyOf(Objects.requireNonNull(terms, "terms are required"));
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds are required"));
            packagePrefix = Objects.requireNonNull(packagePrefix, "packagePrefix is required");
        }
    }

    /** 宣告型概念導向型別成員探索的完整請求 */
    public record GetTypeMembersRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String fullyQualifiedName,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> memberKinds,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> namePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public GetTypeMembersRequestResponse {
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }

    /** 型別成員下一頁後續動作的完整請求 */
    public record DiscoverTypeMembersRequestResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String fullyQualifiedName,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> memberKinds,
            @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> namePrefix,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit) implements RequestResponse {

        public DiscoverTypeMembersRequestResponse {
            memberKinds = List.copyOf(Objects.requireNonNull(
                    memberKinds, "memberKinds are required"));
            namePrefix = Objects.requireNonNull(namePrefix, "namePrefix is required");
        }
    }
}
