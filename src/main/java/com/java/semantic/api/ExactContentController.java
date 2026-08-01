package com.java.semantic.api;

import com.java.semantic.api.dto.ExactContentResponse;
import com.java.semantic.api.dto.ExactContentSegmentResponse;
import com.java.semantic.api.dto.GetMapperFragmentRequest;
import com.java.semantic.api.dto.GetMapperFragmentSegmentRequest;
import com.java.semantic.api.dto.GetMapperStatementRequest;
import com.java.semantic.api.dto.GetMapperStatementSegmentRequest;
import com.java.semantic.api.dto.GetMethodSourceRequest;
import com.java.semantic.api.dto.GetMethodSourceSegmentRequest;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.application.ExactContentApplicationService;
import com.java.semantic.syntax.application.ExactContentQuery;
import com.java.semantic.syntax.application.ExactContentResult;
import com.java.semantic.syntax.application.ExactContentSegment;
import com.java.semantic.syntax.application.ExactContentSegmentQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * typed exact source 與 mapper 證據的 stateless HTTP 邊界
 * segment 不使用 request、session 或 server cache 且 contentRef 不是 lookup key
 */
@RestController
@RequestMapping("/v1/discovery")
public final class ExactContentController {

    private final ExactContentApplicationService service;
    private final ExactContentResponseMapper mapper;

    public ExactContentController(
            ExactContentApplicationService service,
            ExactContentResponseMapper mapper) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    /** 以五欄 canonical MethodTarget 讀取 exact method source */
    @PostMapping("/method-source")
    public ExactContentResponse methodSource(@Valid @RequestBody GetMethodSourceRequest request) {
        ExactContentQuery query = new ExactContentQuery.MethodSource(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()));
        return retrieve(query);
    }

    /** 以五欄 canonical MethodTarget 讀取全部 legal mapper statement 變體 */
    @PostMapping("/method-sql")
    public ExactContentResponse methodSql(@Valid @RequestBody GetMapperStatementRequest request) {
        ExactContentQuery query = new ExactContentQuery.MapperStatement(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()));
        return retrieve(query);
    }

    /** 以完整 fragment identity 讀取 exact mapper XML */
    @PostMapping("/mapper-sql-fragment")
    public ExactContentResponse mapperFragment(
            @Valid @RequestBody GetMapperFragmentRequest request) {
        ExactContentQuery query = new ExactContentQuery.MapperFragment(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                request.fragmentIdentity().toDomain());
        return retrieve(query);
    }

    /** 重送原始 method authority 續讀 exact source segment */
    @PostMapping("/method-source-segment")
    public ExactContentSegmentResponse methodSourceSegment(
            @Valid @RequestBody GetMethodSourceSegmentRequest request) {
        ExactContentQuery query = new ExactContentQuery.MethodSource(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()));
        return readSegment(query, request.contentRef(), request.segmentIndex());
    }

    /** 重送原始 mapper method authority 續讀 exact statement segment */
    @PostMapping("/method-sql-segment")
    public ExactContentSegmentResponse methodSqlSegment(
            @Valid @RequestBody GetMapperStatementSegmentRequest request) {
        ExactContentQuery query = new ExactContentQuery.MapperStatement(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                MethodTargetHttpMapper.toDomain(request.target()));
        return readSegment(query, request.contentRef(), request.segmentIndex());
    }

    /** 重送原始 fragment authority 續讀 exact mapper XML segment */
    @PostMapping("/mapper-fragment-segment")
    public ExactContentSegmentResponse mapperFragmentSegment(
            @Valid @RequestBody GetMapperFragmentSegmentRequest request) {
        ExactContentQuery query = new ExactContentQuery.MapperFragment(
                repositoryId(request.repoId()),
                revision(request.expectedRevision()),
                request.fragmentIdentity().toDomain());
        return readSegment(query, request.contentRef(), request.segmentIndex());
    }

    private ExactContentResponse retrieve(ExactContentQuery query) {
        try {
            ExactContentResult result = service.retrieve(query);
            return mapper.toResponse(query, result);
        } catch (SemanticTargetNotFoundException exception) {
            throw new ExactContentHttpNotFoundException();
        }
    }

    private ExactContentSegmentResponse readSegment(
            ExactContentQuery query,
            String contentRef,
            int segmentIndex) {
        try {
            ExactContentSegment segment = service.readSegment(
                    new ExactContentSegmentQuery(query, contentRef, segmentIndex));
            return mapper.toResponse(segment);
        } catch (SemanticTargetNotFoundException exception) {
            throw new ExactContentHttpNotFoundException();
        }
    }

    private RepositoryId repositoryId(String value) {
        return RepositoryId.of(value);
    }

    private RepositoryRevision revision(String value) {
        return new RepositoryRevision(value);
    }
}
