package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoverConceptsRequest;
import com.java.semantic.api.dto.DiscoverConceptsResponse;
import com.java.semantic.api.dto.DiscoverTypeMembersRequest;
import com.java.semantic.api.dto.DiscoverTypeMembersResponse;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.ConceptKind;
import com.java.semantic.syntax.application.ConceptSearchQuery;
import com.java.semantic.syntax.application.ConceptSearchTerm;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.application.TypeMemberKind;
import com.java.semantic.syntax.application.TypeMemberQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 結構化概念與型別成員探索的無狀態 HTTP 邊界 */
@RestController
@RequestMapping("/v1/discovery")
public final class StructuredDiscoveryController {

    private final ConceptDiscoveryApplicationService conceptService;
    private final TypeMemberDiscoveryApplicationService typeMemberService;
    private final StructuredDiscoveryResponseMapper mapper;

    public StructuredDiscoveryController(
            ConceptDiscoveryApplicationService conceptService,
            TypeMemberDiscoveryApplicationService typeMemberService,
            StructuredDiscoveryResponseMapper mapper) {
        this.conceptService = Objects.requireNonNull(conceptService, "conceptService is required");
        this.typeMemberService = Objects.requireNonNull(
                typeMemberService, "typeMemberService is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    /** 探索固定儲存庫版本的結構化概念 */
    @PostMapping("/concepts")
    public DiscoverConceptsResponse discoverConcepts(
            @Valid @RequestBody DiscoverConceptsRequest request) {
        ConceptSearchQuery query = new ConceptSearchQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.terms().stream()
                        .map(term -> new ConceptSearchTerm(term.value(), term.matchMode()))
                        .toList(),
                distinctKinds(request.kinds()),
                Optional.ofNullable(request.packagePrefix()),
                request.offset(),
                request.limit());
        return mapper.toResponse(conceptService.search(query));
    }

    private static Set<ConceptKind> distinctKinds(List<ConceptKind> kinds) {
        List<ConceptKind> requestedKinds = Objects.requireNonNull(
                kinds, "kinds are required");
        Set<ConceptKind> distinctKinds = new LinkedHashSet<>(requestedKinds);
        if (distinctKinds.size() != requestedKinds.size()) {
            throw new IllegalArgumentException("kinds must not contain duplicates");
        }
        return distinctKinds;
    }

    /** 探索固定儲存庫版本中單一型別的合併成員頁 */
    @PostMapping("/type-members")
    public DiscoverTypeMembersResponse discoverTypeMembers(
            @Valid @RequestBody DiscoverTypeMembersRequest request) {
        Set<TypeMemberKind> memberKinds = new LinkedHashSet<>(request.memberKinds());
        TypeMemberQuery query = new TypeMemberQuery(
                RepositoryId.of(request.repoId()),
                new RepositoryRevision(request.expectedRevision()),
                request.sourceFile(),
                request.fullyQualifiedName(),
                memberKinds,
                Optional.ofNullable(request.namePrefix()),
                request.offset(),
                request.limit());
        return mapper.toResponse(typeMemberService.discover(query));
    }

}
