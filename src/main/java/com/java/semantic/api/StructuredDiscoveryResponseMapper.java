package com.java.semantic.api;

import com.java.semantic.api.dto.ConceptCandidateResponse;
import com.java.semantic.api.dto.ConceptCandidateDetailsResponse;
import com.java.semantic.api.dto.ConceptCoverageResponse;
import com.java.semantic.api.dto.ConceptEvidenceResponse;
import com.java.semantic.api.dto.ConceptIssueSummaryResponse;
import com.java.semantic.api.dto.ConceptPageResponse;
import com.java.semantic.api.dto.DiscoverConceptsResponse;
import com.java.semantic.api.dto.DiscoverTypeMembersResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.AnalyzeCallGraphRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ApiResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ConceptSearchPageRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ConceptSearchTermResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.DiscoverMethodImplementationsRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.DiscoverTypeMembersRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMethodSourceRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMapperStatementRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetTypeMembersRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.RequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ResolveSourceSymbolRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ResolveConceptRequestResponse;
import com.java.semantic.api.dto.identity.SourceSymbolContextPayload;
import com.java.semantic.api.dto.identity.SourceSymbolMethodContextPayload;
import com.java.semantic.api.dto.FieldTypeMemberResponse;
import com.java.semantic.api.dto.ResolveConceptResponse;
import com.java.semantic.api.dto.MethodTypeMemberResponse;
import com.java.semantic.api.dto.MapperMethodCandidateResponse;
import com.java.semantic.api.dto.MapperStatementMappingResponse;
import com.java.semantic.api.dto.TypeMemberResponse;
import com.java.semantic.api.dto.UnavailableDiscoveryFollowUpResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.concept.ConceptCatalogEntry;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.ConceptIdentityOrdering;
import com.java.semantic.syntax.application.concept.ConceptIssueSummary;
import com.java.semantic.syntax.application.concept.ConceptKind;
import com.java.semantic.syntax.application.concept.ConceptPage;
import com.java.semantic.syntax.application.concept.ConceptSearchQuery;
import com.java.semantic.syntax.application.concept.ConceptSearchResult;
import com.java.semantic.syntax.application.concept.ConceptSearchTerm;
import com.java.semantic.syntax.application.concept.RevisionBoundConceptResolution;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUp.AnalyzeCallGraphRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.DiscoverMethodImplementationsRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMethodSourceRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMapperStatementRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetTypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.TypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ResolveSourceSymbolRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ResolveConceptRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.FieldTypeMember;
import com.java.semantic.syntax.application.MethodTypeMember;
import com.java.semantic.syntax.application.concept.MapperStatementMethodMapping;
import com.java.semantic.syntax.application.TypeMember;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SourceExtractionStatus;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 將結構化探索應用結果轉換為穩定且唯讀衍生 identity 的 HTTP 回應 */
@Component
public final class StructuredDiscoveryResponseMapper {

    private static final String CONCEPTS_PATH = "/v1/discovery/concepts";
    private static final String CONCEPTS_OPERATION_ID = "discoverConcepts";

    private static final List<String> LIMITATIONS = List.of("SOURCE_BODY_NOT_SEARCHED");

    private static final String NO_MATCHING_CONCEPT = "NO_MATCHING_STRUCTURED_CONCEPT";

    private static final String REFINE_SEARCH = "REFINE_TERMS_KINDS_OR_PACKAGE_FILTERS";

    private static final String SEARCH_INCOMPLETE = "SEARCH_INCOMPLETE";

    private static final String FIX_SOURCE_OR_RETRY = "FIX_SOURCE_OR_RETRY";

    private final DiscoveryFollowUpFactory followUpFactory;
    private final ConceptIdentityHttpMapper conceptIdentityHttpMapper;
    private final MapperIdentityHttpMapper mapperIdentityHttpMapper;
    private final SourceLocationHttpMapper sourceLocationMapper;

    public StructuredDiscoveryResponseMapper(
            DiscoveryFollowUpFactory followUpFactory,
            ConceptIdentityHttpMapper conceptIdentityHttpMapper,
            MapperIdentityHttpMapper mapperIdentityHttpMapper,
            SourceLocationHttpMapper sourceLocationMapper) {
        this.followUpFactory = Objects.requireNonNull(
                followUpFactory, "followUpFactory is required");
        this.conceptIdentityHttpMapper = Objects.requireNonNull(
                conceptIdentityHttpMapper, "conceptIdentityHttpMapper is required");
        this.mapperIdentityHttpMapper = Objects.requireNonNull(
                mapperIdentityHttpMapper, "mapperIdentityHttpMapper is required");
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
    }

    /** 將概念搜尋結果映射為固定版本 HTTP 回應 */
    public DiscoverConceptsResponse toResponse(ConceptSearchResult result) {
        ConceptSearchResult searchResult = Objects.requireNonNull(result, "result is required");
        List<DiscoveryFollowUpResponse> followUps = searchResult.nextPageQuery()
                .map(this::conceptNextPage)
                .stream()
                .toList();
        return new DiscoverConceptsResponse(
                searchResult.repositoryId().value(),
                searchResult.analyzedRevision().value(),
                searchResult.normalizedTerms().stream().map(ConceptSearchTerm::value).toList(),
                searchResult.searchedKinds().stream().map(Enum::name).toList(),
                searchResult.supportedKinds().stream().map(Enum::name).toList(),
                LIMITATIONS,
                searchResult.candidates().stream()
                        .map(entry -> candidate(searchResult, entry))
                        .toList(),
                page(searchResult.page()),
                coverage(searchResult.coverage()),
                searchResult.issueSummaries().stream().map(this::issue).toList(),
                followUps,
                unavailableFollowUps(searchResult));
    }

    /** 將單一精確 typed concept resolve 結果映射為固定版本 HTTP 回應 */
    public ResolveConceptResponse toResponse(RevisionBoundConceptResolution result) {
        RevisionBoundConceptResolution resolution = Objects.requireNonNull(result, "result is required");
        return new ResolveConceptResponse(
                resolution.repositoryId().value(),
                resolution.analyzedRevision().value(),
                candidate(
                        resolution.repositoryId(),
                        resolution.analyzedRevision(),
                        List.of(),
                        resolution.candidate()));
    }

    /** 將型別成員探索結果映射為帶 discriminator 的固定版本 HTTP 回應 */
    public DiscoverTypeMembersResponse toResponse(TypeMemberResult result) {
        TypeMemberResult memberResult = Objects.requireNonNull(result, "result is required");
        return new DiscoverTypeMembersResponse(
                memberResult.repositoryId().value(),
                memberResult.analyzedRevision().value(),
                JavaSourceIdentityHttpMapper.toPayload(memberResult.sourceType()),
                memberResult.typeKind().name(),
                memberResult.annotations(),
                memberResult.implementedTypes(),
                memberResult.extendedTypes(),
                memberResult.members().stream().map(member -> member(memberResult.sourceType(), member)).toList(),
                page(memberResult.page()),
                coverage(memberResult.coverage()),
                memberResult.availableFollowUps().stream().map(this::followUp).toList());
    }

    private ConceptCandidateResponse candidate(
            ConceptSearchResult result,
            ConceptCatalogEntry entry) {
        return candidate(
                result.repositoryId(),
                result.analyzedRevision(),
                result.normalizedTerms().stream().map(ConceptSearchTerm::value).toList(),
                entry);
    }

    private ConceptCandidateResponse candidate(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            List<String> matchedTerms,
            ConceptCatalogEntry entry) {
        ConceptIdentity identity = entry.identity();
        return new ConceptCandidateResponse(
                conceptIdentityHttpMapper.toResponse(identity),
                entry.displayValue(),
                matchedTerms,
                entry.authority().name(),
                details(repositoryId, revision, entry),
                entry.evidence().stream()
                        .sorted(ConceptIdentityOrdering.comparator())
                        .map(this::evidence)
                        .toList(),
                followUpFactory.forConcept(
                                repositoryId,
                                revision,
                                identity)
                        .stream()
                        .map(this::followUp)
                        .toList());
    }

    private Optional<ConceptCandidateDetailsResponse> details(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            ConceptCatalogEntry entry) {
        if (entry.fieldDetails().isPresent()) {
            return entry.fieldDetails().map(details ->
                    new ConceptCandidateDetailsResponse.FieldDetailsResponse(
                            "FIELD",
                            conceptIdentityHttpMapper.fieldTypeReference(details.declaredType())));
        }
        return mapperStatementMapping(repositoryId, revision, entry).map(mapping ->
                new ConceptCandidateDetailsResponse.MapperStatementDetailsResponse(
                        "MAPPER_STATEMENT",
                        mapping));
    }

    /**
     * mapper method identity 僅取自 production typed evidence
     * 歧義時保留全部 complete targets 供 Agent 逐一檢視而不代選
     */
    private Optional<MapperStatementMappingResponse> mapperStatementMapping(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            ConceptCatalogEntry entry) {
        return entry.mapperStatementMapping().map(mapping -> new MapperStatementMappingResponse(
                mapperIdentityHttpMapper.toPayload(mapping.statementIdentity().statementKey()),
                mapping.status().name(),
                mapping.reason().map(Enum::name),
                mapping.targets().stream()
                        .map(mappedTarget -> mapperMethodCandidate(
                                repositoryId, revision, mapping, mappedTarget))
                        .toList()));
    }

    private MapperMethodCandidateResponse mapperMethodCandidate(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MapperStatementMethodMapping mapping,
            MethodTarget mappedTarget) {
        List<DiscoveryFollowUp> followUps = switch (mapping.status()) {
            case RESOLVED -> followUpFactory.forResolvedMapperStatement(
                    repositoryId, revision, mappedTarget);
            case AMBIGUOUS -> followUpFactory.forAmbiguousMapperMethod(
                    repositoryId, revision, mappedTarget);
            case UNRESOLVED -> followUpFactory.forAmbiguousMapperMethod(
                    repositoryId, revision, mappedTarget);
        };
        return new MapperMethodCandidateResponse(
                JavaSourceIdentityHttpMapper.toPayload(mappedTarget),
                followUps.stream().map(this::followUp).toList());
    }

    private ConceptEvidenceResponse evidence(ConceptIdentity identity) {
        return new ConceptEvidenceResponse(conceptIdentityHttpMapper.toResponse(identity));
    }

    private TypeMemberResponse member(SourceTypeIdentity sourceType, TypeMember member) {
        return switch (member) {
            case MethodTypeMember method -> new MethodTypeMemberResponse(
                    method.kind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(method.target()),
                    method.availableFollowUps().stream().map(this::followUp).toList());
            case FieldTypeMember field -> new FieldTypeMemberResponse(
                    field.kind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(
                            new SourceMemberIdentity.TypeMember(sourceType, field.fieldName()),
                            sourceLocationMapper),
                    field.writtenType(),
                    field.resolvedType(),
                    field.annotations(),
                    field.limitations().stream().map(Enum::name).toList(),
                    field.availableFollowUps().stream().map(this::followUp).toList());
        };
    }

    private ConceptPageResponse page(ConceptPage page) {
        return new ConceptPageResponse(
                page.offset(),
                page.limit(),
                page.returnedCount(),
                page.totalCount(),
                page.hasMore());
    }

    private ConceptCoverageResponse coverage(List<SourceExtractionOutcome> outcomes) {
        int extractedCount = Math.toIntExact(outcomes.stream()
                .filter(outcome -> outcome.status() == SourceExtractionStatus.EXTRACTED)
                .count());
        int syntaxFailedCount = Math.toIntExact(outcomes.stream()
                .filter(outcome -> outcome.status() == SourceExtractionStatus.SYNTAX_FAILED)
                .count());
        String status = syntaxFailedCount > 0 ? "PARTIAL" : "COMPLETE";
        return new ConceptCoverageResponse(
                status,
                outcomes.size(),
                extractedCount,
                syntaxFailedCount);
    }

    private ConceptIssueSummaryResponse issue(ConceptIssueSummary summary) {
        return new ConceptIssueSummaryResponse(summary.reason().name(), summary.count());
    }

    private List<UnavailableDiscoveryFollowUpResponse> unavailableFollowUps(
            ConceptSearchResult result) {
        if (result.page().totalCount() > 0) {
            return List.of();
        }
        boolean partialCoverage = result.coverage().stream()
                .anyMatch(outcome -> outcome.status() == SourceExtractionStatus.SYNTAX_FAILED);
        if (partialCoverage) {
            return List.of(new UnavailableDiscoveryFollowUpResponse(
                    SEARCH_INCOMPLETE,
                    FIX_SOURCE_OR_RETRY));
        }
        return List.of(new UnavailableDiscoveryFollowUpResponse(
                NO_MATCHING_CONCEPT,
                REFINE_SEARCH));
    }

    private DiscoveryFollowUpResponse conceptNextPage(ConceptSearchQuery query) {
        List<ConceptSearchTermResponse> terms = query.terms().stream()
                .map(this::conceptTerm)
                .toList();
        List<String> kinds = query.kinds().stream()
                .sorted(Comparator.comparing(ConceptKind::name))
                .map(Enum::name)
                .toList();
        ConceptSearchPageRequestResponse request = new ConceptSearchPageRequestResponse(
                query.repositoryId().value(),
                query.expectedRevision().value(),
                "ALL",
                terms,
                kinds,
                query.packagePrefix(),
                query.offset(),
                query.limit());
        return new DiscoveryFollowUpResponse(
                DiscoveryFollowUp.Operation.GET_NEXT_PAGE.name(),
                new ApiResponse("POST", CONCEPTS_PATH, CONCEPTS_OPERATION_ID),
                request);
    }

    DiscoveryFollowUpResponse followUp(DiscoveryFollowUp followUp) {
        return new DiscoveryFollowUpResponse(
                followUp.operation().name(),
                new ApiResponse(
                        followUp.api().method(),
                        followUp.api().path(),
                        followUp.api().operationId()),
                request(followUp.request()));
    }

    private RequestResponse request(DiscoveryFollowUp.RequestProjection request) {
        return switch (request) {
            case GetMethodSourceRequest source -> new GetMethodSourceRequestResponse(
                    source.repoId(),
                    source.expectedRevision(),
                    JavaSourceIdentityHttpMapper.toPayload(source.target()));
            case GetMapperStatementRequest statement -> new GetMapperStatementRequestResponse(
                    statement.repoId(),
                    statement.expectedRevision(),
                    JavaSourceIdentityHttpMapper.toPayload(statement.target()));
            case AnalyzeCallGraphRequest graph -> new AnalyzeCallGraphRequestResponse(
                    graph.repoId(),
                    graph.expectedRevision(),
                    graph.depth(),
                    JavaSourceIdentityHttpMapper.toPayload(graph.target()));
            case DiscoverMethodImplementationsRequest implementations ->
                    new DiscoverMethodImplementationsRequestResponse(
                            implementations.repoId(),
                            implementations.expectedRevision(),
                            JavaSourceIdentityHttpMapper.toPayload(implementations.declarationTarget()));
            case ResolveConceptRequest concept -> new ResolveConceptRequestResponse(
                    concept.repoId(),
                    concept.expectedRevision(),
                    conceptIdentityHttpMapper.toResponse(concept.identity()));
            case GetTypeMembersRequest members -> new GetTypeMembersRequestResponse(
                    members.repoId(),
                    members.expectedRevision(),
                    JavaSourceIdentityHttpMapper.toPayload(members.sourceType()),
                    members.memberKinds().stream().map(Enum::name).toList(),
                    members.namePrefix(),
                    members.offset(),
                    members.limit());
            case TypeMembersRequest members -> new DiscoverTypeMembersRequestResponse(
                    members.repoId(),
                    members.expectedRevision(),
                    JavaSourceIdentityHttpMapper.toPayload(members.sourceType()),
                    members.memberKinds().stream().map(Enum::name).toList(),
                    members.namePrefix(),
                    members.offset(),
                    members.limit());
            case ResolveSourceSymbolRequest sourceSymbol -> new ResolveSourceSymbolRequestResponse(
                    sourceSymbol.repoId(),
                    sourceSymbol.expectedRevision(),
                    new SourceSymbolContextPayload(
                            JavaSourceIdentityHttpMapper.toPayload(sourceSymbol.context().javaType()),
                            sourceSymbol.context().sourceFile(),
                            sourceSymbol.context().method().map(method -> new SourceSymbolMethodContextPayload(
                                    method.name(), method.parameterTypes()))),
                    sourceSymbol.symbol(),
                    sourceSymbol.position().map(sourceLocationMapper::toPayload));
        };
    }

    private ConceptSearchTermResponse conceptTerm(ConceptSearchTerm term) {
        return new ConceptSearchTermResponse(term.value(), term.matchMode().name());
    }

}
