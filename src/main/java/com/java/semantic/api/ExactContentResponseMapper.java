package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ApiResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMapperFragmentRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMapperFragmentSegmentRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMapperStatementSegmentRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMethodSourceSegmentRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.RequestResponse;
import com.java.semantic.api.dto.ExactContentResponse;
import com.java.semantic.api.dto.ExactContentSegmentResponse;
import com.java.semantic.api.dto.ExactContentVariantResponse;
import com.java.semantic.api.dto.MapperIncludeResolutionResponse;
import com.java.semantic.api.dto.MapperFragmentIdentityResponse;
import com.java.semantic.api.dto.MapperStatementIdentityResponse;
import com.java.semantic.syntax.application.ExactContentQuery;
import com.java.semantic.syntax.application.ExactContentResult;
import com.java.semantic.syntax.application.ExactContentSegment;
import com.java.semantic.syntax.application.ExactContentSegmentQuery;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 將 exact content 投影為 endpoint-specific stateless continuation 契約
 * 每次續讀都重送原始 authority 且 contentRef 只用於一致性驗證
 */
@Component
public final class ExactContentResponseMapper {

    private static final String METHOD_SOURCE_SEGMENT_PATH =
            "/v1/discovery/method-source-segment";
    private static final String METHOD_SQL_SEGMENT_PATH =
            "/v1/discovery/method-sql-segment";
    private static final String MAPPER_FRAGMENT_PATH =
            "/v1/discovery/mapper-sql-fragment";
    private static final String MAPPER_FRAGMENT_SEGMENT_PATH =
            "/v1/discovery/mapper-fragment-segment";

    /** 將 inline 或 oversized exact content 投影為封閉 HTTP 回應 */
    public ExactContentResponse toResponse(ExactContentQuery query, ExactContentResult result) {
        ExactContentQuery contentQuery = Objects.requireNonNull(query, "query is required");
        ExactContentResult contentResult = Objects.requireNonNull(result, "result is required");
        List<ExactContentVariantResponse> variants = contentResult.variants().stream()
                .map(variant -> variant(contentQuery, variant))
                .toList();
        return new ExactContentResponse(
                contentResult.repositoryId().value(),
                contentResult.analyzedRevision().value(),
                variants);
    }

    /** 將單一 segment 與下一段完整 stateless request 投影為封閉 HTTP 回應 */
    public ExactContentSegmentResponse toResponse(ExactContentSegment segment) {
        ExactContentSegment contentSegment = Objects.requireNonNull(segment, "segment is required");
        Optional<DiscoveryFollowUpResponse> nextFollowUp = contentSegment.nextSegmentQuery()
                .map(this::segmentFollowUp);
        return new ExactContentSegmentResponse(
                contentSegment.contentRef(),
                contentSegment.segmentIndex(),
                contentSegment.segmentCount(),
                contentSegment.utf8ByteCount(),
                contentSegment.content(),
                nextFollowUp);
    }

    private ExactContentVariantResponse variant(
            ExactContentQuery query,
            ExactContentResult.ContentVariant variant) {
        ExactContentResult.Content content = variant.content();
        List<DiscoveryFollowUpResponse> followUps = content.contentRef()
                .map(contentRef -> List.of(segmentFollowUp(
                        new ExactContentSegmentQuery(query, contentRef, 0))))
                .orElseGet(List::of);
        List<MapperIncludeResolutionResponse> includeResolutions =
                variant.includeResolutions().stream()
                        .map(resolution -> includeResolution(query, resolution))
                        .toList();
        return new ExactContentVariantResponse(
                variant.statementIdentity().map(this::statementIdentity),
                variant.fragmentIdentity().map(this::fragmentIdentity),
                content.inlineContent(),
                content.contentRef(),
                content.utf8ByteCount(),
                content.segmentCount(),
                includeResolutions,
                followUps);
    }

    /**
     * typed evidence 已完成 include 解析與 canonical 排序
     * ambiguous 候選會全部投影為相同 envelope 的可執行後續動作
     */
    private MapperIncludeResolutionResponse includeResolution(
            ExactContentQuery query,
            ExactContentResult.IncludeResolution resolution) {
        List<DiscoveryFollowUpResponse> followUps = resolution.fragmentIdentities().stream()
                .map(identity -> fragmentFollowUp(query, identity))
                .toList();
        return new MapperIncludeResolutionResponse(
                resolution.refId(),
                resolution.status(),
                followUps);
    }

    private DiscoveryFollowUpResponse fragmentFollowUp(
            ExactContentQuery query,
            MapperFragmentIdentity identity) {
        return followUp(
                "GET_MAPPER_FRAGMENT",
                MAPPER_FRAGMENT_PATH,
                "getMapperFragment",
                new GetMapperFragmentRequestResponse(
                        query.repositoryId().value(),
                        query.expectedRevision().value(),
                        fragmentIdentity(identity)));
    }

    private DiscoveryFollowUpResponse segmentFollowUp(ExactContentSegmentQuery segmentQuery) {
        ExactContentQuery query = segmentQuery.contentQuery();
        return switch (query) {
            case ExactContentQuery.MethodSource source -> followUp(
                    "GET_METHOD_SOURCE_SEGMENT",
                    METHOD_SOURCE_SEGMENT_PATH,
                    "getMethodSourceSegment",
                    new GetMethodSourceSegmentRequestResponse(
                            source.repositoryId().value(),
                            source.expectedRevision().value(),
                            MethodTargetHttpMapper.toResponse(source.target()),
                            segmentQuery.contentRef(),
                            segmentQuery.segmentIndex()));
            case ExactContentQuery.MapperStatement statement -> followUp(
                    "GET_METHOD_SQL_SEGMENT",
                    METHOD_SQL_SEGMENT_PATH,
                    "getMethodSqlSegment",
                    new GetMapperStatementSegmentRequestResponse(
                            statement.repositoryId().value(),
                            statement.expectedRevision().value(),
                            MethodTargetHttpMapper.toResponse(statement.target()),
                            segmentQuery.contentRef(),
                            segmentQuery.segmentIndex()));
            case ExactContentQuery.MapperFragment fragment -> followUp(
                    "GET_MAPPER_FRAGMENT_SEGMENT",
                    MAPPER_FRAGMENT_SEGMENT_PATH,
                    "getMapperFragmentSegment",
                    new GetMapperFragmentSegmentRequestResponse(
                            fragment.repositoryId().value(),
                            fragment.expectedRevision().value(),
                            fragmentIdentity(fragment.fragmentIdentity()),
                            segmentQuery.contentRef(),
                            segmentQuery.segmentIndex()));
        };
    }

    private DiscoveryFollowUpResponse followUp(
            String operation,
            String path,
            String operationId,
            RequestResponse request) {
        return new DiscoveryFollowUpResponse(
                operation,
                new ApiResponse("POST", path, operationId),
                request);
    }

    private MapperStatementIdentityResponse statementIdentity(MapperStatementIdentity identity) {
        return new MapperStatementIdentityResponse(
                identity.namespace(),
                identity.statementId(),
                identity.resourcePath(),
                identity.databaseId(),
                identity.documentOrdinal(),
                identity.representation());
    }

    private MapperFragmentIdentityResponse fragmentIdentity(MapperFragmentIdentity identity) {
        return new MapperFragmentIdentityResponse(
                identity.namespace(),
                identity.fragmentId(),
                identity.resourcePath(),
                identity.documentOrdinal(),
                identity.representation());
    }
}
