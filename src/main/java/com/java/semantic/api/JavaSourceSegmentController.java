package com.java.semantic.api;

import com.java.semantic.api.dto.JavaSourceSegmentRequest;
import com.java.semantic.api.dto.JavaSourceSegmentResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.JavaSourceSegmentApplicationService;
import com.java.semantic.syntax.application.JavaSourceSegmentQuery;
import com.java.semantic.syntax.application.JavaSourceSegmentResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 提供 repository-contained 且受 byte bound 限制的 Java source segment */
@RestController
public final class JavaSourceSegmentController {

    private final JavaSourceSegmentApplicationService applicationService;
    private final SourceLocationHttpMapper sourceLocationMapper;

    public JavaSourceSegmentController(
            JavaSourceSegmentApplicationService applicationService,
            SourceLocationHttpMapper sourceLocationMapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
    }

    /** 讀取 exact range 與可容納的 bounded context */
    @PostMapping("/v1/discovery/java-source-segment")
    public JavaSourceSegmentResponse getJavaSourceSegment(
            @Valid @RequestBody JavaSourceSegmentRequest request) {
        JavaSourceSegmentQuery query = new JavaSourceSegmentQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                sourceLocationMapper.toSourceRange(request.sourceRange()),
                request.contextLines());
        JavaSourceSegmentResult result = applicationService.read(query);
        return new JavaSourceSegmentResponse(
                result.repositoryId().value(),
                result.analyzedRevision().value(),
                sourceLocationMapper.toSourceRange(result.contentRange()),
                result.content(),
                result.contextTruncated(),
                result.returnedUtf8Bytes());
    }
}
