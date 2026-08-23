package com.java.semantic.api;

import com.java.semantic.api.dto.InternalSourceReferenceRequest;
import com.java.semantic.api.dto.InternalSourceReferenceResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.InternalSourceReferenceApplicationService;
import com.java.semantic.semantic.application.InternalSourceReferenceQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 提供固定 revision 與 exact target 的 repository-local reference 探索 */
@RestController
public final class InternalSourceReferenceController {

    private final InternalSourceReferenceApplicationService applicationService;
    private final ExactSourceDeclarationTargetHttpMapper targetMapper;
    private final InternalSourceReferenceResponseMapper responseMapper;

    public InternalSourceReferenceController(
            InternalSourceReferenceApplicationService applicationService,
            ExactSourceDeclarationTargetHttpMapper targetMapper,
            InternalSourceReferenceResponseMapper responseMapper) {
        this.applicationService = Objects.requireNonNull(applicationService, "applicationService is required");
        this.targetMapper = Objects.requireNonNull(targetMapper, "targetMapper is required");
        this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper is required");
    }

    /** 查詢完成聚合後的 deterministic reference group page */
    @PostMapping("/v1/discovery/internal-references")
    public InternalSourceReferenceResponse findInternalReferences(
            @Valid @RequestBody InternalSourceReferenceRequest request) {
        InternalSourceReferenceQuery query = new InternalSourceReferenceQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                targetMapper.toDomain(request.target()),
                request.offset(),
                request.limit());
        return responseMapper.toResponse(query, applicationService.find(query));
    }
}
