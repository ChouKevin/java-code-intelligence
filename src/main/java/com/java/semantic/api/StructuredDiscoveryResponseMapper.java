package com.java.semantic.api;

import com.java.semantic.api.dto.ConceptCandidateResponse;
import com.java.semantic.api.dto.ConceptCoverageResponse;
import com.java.semantic.api.dto.ConceptEvidenceResponse;
import com.java.semantic.api.dto.ConceptIssueSummaryResponse;
import com.java.semantic.api.dto.ConceptPageResponse;
import com.java.semantic.api.dto.DiscoverConceptsResponse;
import com.java.semantic.api.dto.DiscoverTypeMembersResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.AnalyzeCallGraphRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ApiResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ConceptTermResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.DiscoverConceptsRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.DiscoverMethodImplementationsRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.DiscoverTypeMembersRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMethodSourceRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetMapperStatementRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.GetTypeMembersRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.RequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.ResolveSourceSymbolRequestResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.SourceSymbolContextResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse.SourceSymbolMethodContextResponse;
import com.java.semantic.api.dto.FieldTypeMemberResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.api.dto.PositionResponse;
import com.java.semantic.api.dto.MethodTypeMemberResponse;
import com.java.semantic.api.dto.MapperMethodCandidateResponse;
import com.java.semantic.api.dto.MapperStatementMappingResponse;
import com.java.semantic.api.dto.TypeMemberResponse;
import com.java.semantic.api.dto.UnavailableDiscoveryFollowUpResponse;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptCatalogEntry;
import com.java.semantic.syntax.application.ConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.ConceptIssueSummary;
import com.java.semantic.syntax.application.ConceptKind;
import com.java.semantic.syntax.application.ConceptPage;
import com.java.semantic.syntax.application.ConceptSearchQuery;
import com.java.semantic.syntax.application.ConceptSearchResult;
import com.java.semantic.syntax.application.ConceptSearchTerm;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUp.AnalyzeCallGraphRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ConceptDiscoveryRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ConceptTermRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.DiscoverMethodImplementationsRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMethodSourceRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMapperStatementRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetTypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.TypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ResolveSourceSymbolRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.FieldTypeMember;
import com.java.semantic.syntax.application.MethodTypeMember;
import com.java.semantic.syntax.application.MapperStatementMethodMapping;
import com.java.semantic.syntax.application.TypeMember;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SourceExtractionStatus;
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

    private static final Comparator<ConceptIdentity> EVIDENCE_ORDER = Comparator
            .comparing(ConceptIdentity::kind)
            .thenComparing(ConceptIdentity::canonicalOrderKey);

    private final DiscoveryFollowUpFactory followUpFactory;

    public StructuredDiscoveryResponseMapper(DiscoveryFollowUpFactory followUpFactory) {
        this.followUpFactory = Objects.requireNonNull(
                followUpFactory, "followUpFactory is required");
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

    /** 將型別成員探索結果映射為帶 discriminator 的固定版本 HTTP 回應 */
    public DiscoverTypeMembersResponse toResponse(TypeMemberResult result) {
        TypeMemberResult memberResult = Objects.requireNonNull(result, "result is required");
        return new DiscoverTypeMembersResponse(
                memberResult.repositoryId().value(),
                memberResult.analyzedRevision().value(),
                memberResult.sourceFile(),
                memberResult.fullyQualifiedName(),
                memberResult.typeKind().name(),
                memberResult.annotations(),
                memberResult.implementedTypes(),
                memberResult.extendedTypes(),
                memberResult.members().stream().map(this::member).toList(),
                page(memberResult.page()),
                coverage(memberResult.coverage()),
                memberResult.availableFollowUps().stream().map(this::followUp).toList());
    }

    private ConceptCandidateResponse candidate(
            ConceptSearchResult result,
            ConceptCatalogEntry entry) {
        ConceptIdentity identity = entry.identity();
        return new ConceptCandidateResponse(
                entry.kind().name(),
                entry.canonicalValue(),
                entry.displayValue(),
                result.normalizedTerms().stream().map(ConceptSearchTerm::value).toList(),
                entry.packageName(),
                entry.declaringType(),
                entry.authority().name(),
                subject(identity),
                target(identity),
                mapperStatementMapping(result, entry),
                entry.evidence().stream()
                        .sorted(EVIDENCE_ORDER)
                        .map(this::evidence)
                        .toList(),
                followUpFactory.forConcept(
                                result.repositoryId(),
                                result.analyzedRevision(),
                                identity)
                        .stream()
                        .map(this::followUp)
                        .toList());
    }

    /**
     * mapper method identity 僅取自 production typed evidence
     * 歧義時保留全部 complete targets 供 Agent 逐一檢視而不代選
     */
    private Optional<MapperStatementMappingResponse> mapperStatementMapping(
            ConceptSearchResult result,
            ConceptCatalogEntry entry) {
        return entry.mapperStatementMapping().map(mapping -> new MapperStatementMappingResponse(
                mapping.statementIdentity().namespace(),
                mapping.statementIdentity().statementId(),
                mapping.status().name(),
                mapping.reason().map(Enum::name),
                mapping.targets().stream()
                        .map(mappedTarget -> mapperMethodCandidate(result, mapping, mappedTarget))
                        .toList()));
    }

    private MapperMethodCandidateResponse mapperMethodCandidate(
            ConceptSearchResult result,
            MapperStatementMethodMapping mapping,
            MethodTarget mappedTarget) {
        List<DiscoveryFollowUp> followUps = switch (mapping.status()) {
            case RESOLVED -> followUpFactory.forResolvedMapperStatement(
                    result.repositoryId(), result.analyzedRevision(), mappedTarget);
            case AMBIGUOUS -> followUpFactory.forAmbiguousMapperMethod(
                    result.repositoryId(), result.analyzedRevision(), mappedTarget);
            case UNRESOLVED -> followUpFactory.forAmbiguousMapperMethod(
                    result.repositoryId(), result.analyzedRevision(), mappedTarget);
        };
        return new MapperMethodCandidateResponse(
                MethodTargetHttpMapper.toResponse(mappedTarget),
                followUps.stream().map(this::followUp).toList());
    }

    private ConceptEvidenceResponse evidence(ConceptIdentity identity) {
        if (identity instanceof MapperStatementVariantEvidenceIdentity variantIdentity) {
            return new ConceptEvidenceResponse(
                    identity.kind().name(),
                    subject(identity),
                    Optional.empty(),
                    Optional.of(variantIdentity.mapperStatement().resourcePath()),
                    variantIdentity.mapperStatement().databaseId(),
                    Optional.of(variantIdentity.mapperStatement().documentOrdinal()),
                    Optional.of(variantIdentity.mapperStatement().representation().name()));
        }
        return new ConceptEvidenceResponse(
                identity.kind().name(),
                subject(identity),
                target(identity),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private Optional<String> subject(ConceptIdentity identity) {
        return switch (identity) {
            case TypeConceptIdentity typeIdentity ->
                    Optional.of(typeIdentity.sourceFile() + "::" + typeIdentity.fullyQualifiedType());
            case MethodConceptIdentity ignored -> Optional.empty();
            case FieldConceptIdentity fieldIdentity -> Optional.of(
                    fieldIdentity.sourceFile() + "::" + fieldIdentity.ownerType()
                            + "#" + fieldIdentity.fieldName() + ":" + fieldIdentity.declaredType());
            case AnnotationUsageConceptIdentity annotationIdentity -> Optional.of(
                    annotationIdentity.sourceFile() + "::"
                            + annotationIdentity.annotatedDeclaration().displayValue()
                            + "@" + annotationIdentity.annotationIdentity());
            case TypeUsageConceptIdentity typeUsageIdentity -> Optional.of(
                    typeUsageIdentity.sourceFile() + "::"
                            + typeUsageIdentity.ownerDeclaration().displayValue()
                            + "[" + typeUsageIdentity.usageLocation().slot().name()
                            + ":" + typeUsageIdentity.usageLocation().index() + "]"
                            + ":" + typeUsageIdentity.resolvedType());
            case ApiRouteConceptIdentity routeIdentity ->
                    Optional.of(routeIdentity.httpVerb() + " " + routeIdentity.route());
            case MqDestinationConceptIdentity destinationIdentity ->
                    Optional.of(destinationIdentity.broker().name() + ":" + destinationIdentity.destination());
            case ScheduleConceptIdentity scheduleIdentity -> Optional.of(
                    scheduleIdentity.triggerKind().name() + ":"
                            + scheduleIdentity.triggerValue().orElse("<unresolved>"));
            case MapperStatementConceptIdentity mapperIdentity ->
                    Optional.of(mapperIdentity.namespace() + "#" + mapperIdentity.statementId());
            case MapperStatementVariantEvidenceIdentity variantIdentity ->
                    Optional.of(variantIdentity.mapperStatement().namespace() + "#"
                            + variantIdentity.mapperStatement().statementId());
        };
    }

    private Optional<MethodTargetResponse> target(ConceptIdentity identity) {
        return switch (identity) {
            case MethodConceptIdentity methodIdentity -> Optional.of(MethodTargetHttpMapper.toResponse(methodIdentity.target()));
            case ApiRouteConceptIdentity routeIdentity -> Optional.of(MethodTargetHttpMapper.toResponse(routeIdentity.target()));
            case MqDestinationConceptIdentity destinationIdentity ->
                    Optional.of(MethodTargetHttpMapper.toResponse(destinationIdentity.target()));
            case ScheduleConceptIdentity scheduleIdentity -> Optional.of(MethodTargetHttpMapper.toResponse(scheduleIdentity.target()));
            case AnnotationUsageConceptIdentity annotationIdentity ->
                    declarationTarget(annotationIdentity.annotatedDeclaration());
            case TypeUsageConceptIdentity typeUsageIdentity ->
                    declarationTarget(typeUsageIdentity.ownerDeclaration());
            case TypeConceptIdentity ignored -> Optional.empty();
            case FieldConceptIdentity ignored -> Optional.empty();
            case MapperStatementConceptIdentity ignored -> Optional.empty();
            case MapperStatementVariantEvidenceIdentity ignored -> Optional.empty();
        };
    }

    private Optional<MethodTargetResponse> declarationTarget(
            ConceptIdentity.DeclarationSubjectIdentity subject) {
        return switch (subject) {
            case ResolvedMethodDeclarationSubjectIdentity methodSubject ->
                    Optional.of(MethodTargetHttpMapper.toResponse(methodSubject.target()));
            case TypeDeclarationSubjectIdentity ignored -> Optional.empty();
            case UnresolvedMethodDeclarationSubjectIdentity ignored -> Optional.empty();
            case FieldDeclarationSubjectIdentity ignored -> Optional.empty();
        };
    }

    private TypeMemberResponse member(TypeMember member) {
        return switch (member) {
            case MethodTypeMember method -> new MethodTypeMemberResponse(
                    method.kind().name(),
                    MethodTargetHttpMapper.toResponse(method.target()),
                    method.availableFollowUps().stream().map(this::followUp).toList());
            case FieldTypeMember field -> new FieldTypeMemberResponse(
                    field.kind().name(),
                    field.fieldName(),
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
        List<ConceptTermResponse> terms = query.terms().stream()
                .map(this::conceptTerm)
                .toList();
        List<String> kinds = query.kinds().stream()
                .sorted(Comparator.comparing(ConceptKind::name))
                .map(Enum::name)
                .toList();
        DiscoverConceptsRequestResponse request = new DiscoverConceptsRequestResponse(
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
                    MethodTargetHttpMapper.toResponse(source.target()));
            case GetMapperStatementRequest statement -> new GetMapperStatementRequestResponse(
                    statement.repoId(),
                    statement.expectedRevision(),
                    MethodTargetHttpMapper.toResponse(statement.target()));
            case AnalyzeCallGraphRequest graph -> new AnalyzeCallGraphRequestResponse(
                    graph.repoId(),
                    graph.expectedRevision(),
                    graph.depth(),
                    MethodTargetHttpMapper.toResponse(graph.target()));
            case DiscoverMethodImplementationsRequest implementations ->
                    new DiscoverMethodImplementationsRequestResponse(
                            implementations.repoId(),
                            implementations.expectedRevision(),
                            MethodTargetHttpMapper.toResponse(implementations.declarationTarget()));
            case ConceptDiscoveryRequest concepts -> new DiscoverConceptsRequestResponse(
                    concepts.repoId(),
                    concepts.expectedRevision(),
                    concepts.operator(),
                    concepts.terms().stream().map(this::conceptTerm).toList(),
                    concepts.kinds().stream().map(Enum::name).toList(),
                    concepts.packagePrefix(),
                    concepts.offset(),
                    concepts.limit());
            case GetTypeMembersRequest members -> new GetTypeMembersRequestResponse(
                    members.repoId(),
                    members.expectedRevision(),
                    members.sourceFile(),
                    members.fullyQualifiedName(),
                    members.memberKinds().stream().map(Enum::name).toList(),
                    members.namePrefix(),
                    members.offset(),
                    members.limit());
            case TypeMembersRequest members -> new DiscoverTypeMembersRequestResponse(
                    members.repoId(),
                    members.expectedRevision(),
                    members.sourceFile(),
                    members.fullyQualifiedName(),
                    members.memberKinds().stream().map(Enum::name).toList(),
                    members.namePrefix(),
                    members.offset(),
                    members.limit());
            case ResolveSourceSymbolRequest sourceSymbol -> new ResolveSourceSymbolRequestResponse(
                    sourceSymbol.repoId(),
                    sourceSymbol.expectedRevision(),
                    new SourceSymbolContextResponse(
                            sourceSymbol.context().fullyQualifiedType(),
                            sourceSymbol.context().sourceFile(),
                            sourceSymbol.context().method().map(method -> new SourceSymbolMethodContextResponse(
                                    method.name(), method.parameterTypes()))),
                    sourceSymbol.symbol(),
                    sourceSymbol.position().map(position -> new PositionResponse(
                            position.line(), position.character())));
        };
    }

    private ConceptTermResponse conceptTerm(ConceptSearchTerm term) {
        return new ConceptTermResponse(term.value(), term.matchMode().name());
    }

    private ConceptTermResponse conceptTerm(ConceptTermRequest term) {
        return new ConceptTermResponse(term.value(), term.matchMode().name());
    }

}
