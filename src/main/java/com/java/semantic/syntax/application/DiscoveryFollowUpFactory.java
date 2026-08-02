package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.DiscoveryFollowUp.AnalyzeCallGraphRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.DiscoverMethodImplementationsRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMethodSourceRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMapperStatementRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetTypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.Operation;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ResolveSourceSymbolRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ResolveConceptRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.TypeMembersRequest;
import com.java.semantic.syntax.domain.SyntaxPosition;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 依固定 HTTP 契約建立不需 Agent 重建參數的 discovery follow-up */
public final class DiscoveryFollowUpFactory {

    private static final int GRAPH_DEPTH = 2;

    private static final int DEFAULT_LIMIT = 50;

    /** 依 typed identity 建立方法分析或安全型別成員 follow-up */
    public List<DiscoveryFollowUp> forConcept(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            ConceptIdentity identity) {
        ConceptIdentity conceptIdentity = Objects.requireNonNull(identity, "identity is required");
        return switch (conceptIdentity) {
            case TypeConceptIdentity typeIdentity -> forType(
                    repositoryId,
                    revision,
                    typeIdentity.type());
            case MethodConceptIdentity methodIdentity ->
                    forConceptMethod(repositoryId, revision, methodIdentity.target());
            case FieldConceptIdentity fieldIdentity -> forType(
                    repositoryId,
                    revision,
                    fieldIdentity.field().ownerType());
            case AnnotationUsageConceptIdentity annotationIdentity -> forDeclaration(
                    repositoryId,
                    revision,
                    annotationIdentity.annotatedDeclaration());
            case TypeUsageConceptIdentity typeUsageIdentity -> forDeclaration(
                    repositoryId,
                    revision,
                    typeUsageIdentity.owner());
            case ApiRouteConceptIdentity routeIdentity ->
                    forConceptMethod(repositoryId, revision, routeIdentity.target());
            case MqDestinationConceptIdentity destinationIdentity ->
                    forConceptMethod(repositoryId, revision, destinationIdentity.target());
            case ScheduleConceptIdentity scheduleIdentity ->
                    forConceptMethod(repositoryId, revision, scheduleIdentity.target());
            case MapperStatementConceptIdentity ignored -> List.of();
            case MapperStatementVariantEvidenceIdentity ignored -> List.of();
        };
    }

    /** 建立目前已可執行的 exact source、雙向 graph 與 implementation discovery 方法請求 */
    public List<DiscoveryFollowUp> forMethod(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTarget target) {
        String repoId = repositoryId(repositoryId);
        String expectedRevision = revision(revision);
        MethodTarget methodTarget = Objects.requireNonNull(target, "target is required");
        return executableMethodAnalysis(repoId, expectedRevision, methodTarget);
    }

    /** source type identity 導向完整 type-member 與 typed concept resolve */
    public List<DiscoveryFollowUp> forSourceType(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceTypeIdentity sourceType) {
        SourceTypeIdentity type = Objects.requireNonNull(sourceType, "sourceType is required");
        return Stream.concat(
                forType(repositoryId, revision, type).stream(),
                forResolvedFieldType(
                        repositoryId,
                        revision,
                        Optional.of(new TypeConceptIdentity(type))).stream())
                .toList();
    }

    /** type ambiguity retry 插入 source file 並移除 stale position */
    public DiscoveryFollowUp forSourceTypeContextRetry(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceSymbolResolutionQuery original,
            String sourceFile) {
        SourceSymbolContext context = original.context();
        SourceSymbolContext selected = new SourceSymbolContext(
                context.javaType(), Optional.of(sourceFile), context.method());
        return sourceSymbolRetry(repositoryId, revision, original, selected, Optional.empty());
    }

    /** method ambiguity retry 採用 canonical target signature 並移除 stale position */
    public DiscoveryFollowUp forSourceMethodContextRetry(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceSymbolResolutionQuery original,
            MethodTarget target) {
        MethodTarget selectedTarget = Objects.requireNonNull(target, "target is required");
        SourceSymbolContext selected = new SourceSymbolContext(
                original.context().javaType(),
                Optional.of(selectedTarget.sourceFile()),
                Optional.of(new SourceSymbolContext.MethodContext(
                        selectedTarget.methodName(), Optional.of(selectedTarget.parameterTypes()))));
        return sourceSymbolRetry(repositoryId, revision, original, selected, Optional.empty());
    }

    /** symbol ambiguity retry 固定 source file 與 representative occurrence start */
    public DiscoveryFollowUp forSourceSymbolCandidateRetry(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceSymbolResolutionQuery original,
            SourceSymbolCandidate candidate) {
        SourceSymbolCandidate selectedCandidate = Objects.requireNonNull(candidate, "candidate is required");
        SourceSymbolContext context = original.context();
        SourceSymbolContext selected = new SourceSymbolContext(
                context.javaType(),
                Optional.of(selectedCandidate.representativeOccurrence().sourceFile()),
                context.method());
        return sourceSymbolRetry(
                repositoryId,
                revision,
                original,
                selected,
                Optional.of(selectedCandidate.representativeOccurrence().range().start()));
    }

    /** 唯一 mapper method 可直接取得其 exact SQL variants */
    public List<DiscoveryFollowUp> forResolvedMapperStatement(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTarget target) {
        GetMapperStatementRequest request = new GetMapperStatementRequest(
                repositoryId(repositoryId),
                revision(revision),
                Objects.requireNonNull(target, "target is required"));
        return List.of(followUp(Operation.GET_METHOD_SQL, request));
    }

    /** 歧義 mapper method 僅允許逐一檢視 exact source 以供判讀 */
    public List<DiscoveryFollowUp> forAmbiguousMapperMethod(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTarget target) {
        GetMethodSourceRequest request = new GetMethodSourceRequest(
                repositoryId(repositoryId),
                revision(revision),
                Objects.requireNonNull(target, "target is required"));
        return List.of(followUp(Operation.GET_METHOD_SOURCE, request));
    }

    private List<DiscoveryFollowUp> forConceptMethod(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            MethodTarget target) {
        return executableMethodAnalysis(
                repositoryId(repositoryId),
                revision(revision),
                Objects.requireNonNull(target, "target is required"));
    }

    private List<DiscoveryFollowUp> executableMethodAnalysis(
            String repoId,
            String expectedRevision,
            MethodTarget methodTarget) {
        return List.of(
                followUp(
                        Operation.GET_METHOD_SOURCE,
                        new GetMethodSourceRequest(repoId, expectedRevision, methodTarget)),
                followUp(
                        Operation.ANALYZE_OUTGOING_CALL_GRAPH,
                        new AnalyzeCallGraphRequest(repoId, expectedRevision, GRAPH_DEPTH, methodTarget)),
                followUp(
                        Operation.ANALYZE_INCOMING_CALL_GRAPH,
                        new AnalyzeCallGraphRequest(repoId, expectedRevision, GRAPH_DEPTH, methodTarget)),
                followUp(
                        Operation.DISCOVER_METHOD_IMPLEMENTATIONS,
                        new DiscoverMethodImplementationsRequest(repoId, expectedRevision, methodTarget)));
    }

    /**
     * 已解析且 source-qualified 的欄位型別直接使用 typed TYPE resolve
     * 外部 dependency 可以合法沒有 follow-up，絕不推測來源檔
     */
    public List<DiscoveryFollowUp> forResolvedFieldType(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            Optional<TypeConceptIdentity> resolvedType) {
        Optional<TypeConceptIdentity> type = Objects.requireNonNull(resolvedType, "resolvedType is required");
        if (type.isEmpty()) {
            return List.of();
        }
        ResolveConceptRequest request = new ResolveConceptRequest(
                repositoryId(repositoryId),
                revision(revision),
                type.orElseThrow());
        return List.of(followUp(Operation.RESOLVE_CONCEPT, request));
    }

    /** 建立保留完整型別查詢條件且只替換 offset 的下一頁請求 */
    public DiscoveryFollowUp nextTypeMemberPage(TypeMemberQuery query, int nextOffset) {
        TypeMemberQuery nextQuery = Objects.requireNonNull(query, "query is required").nextPage(nextOffset);
        List<TypeMemberKind> orderedKinds = nextQuery.memberKinds().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        TypeMembersRequest request = new TypeMembersRequest(
                nextQuery.repositoryId().value(),
                nextQuery.expectedRevision().value(),
                nextQuery.sourceType(),
                orderedKinds,
                nextQuery.namePrefix(),
                nextQuery.offset(),
                nextQuery.limit());
        return followUp(Operation.GET_NEXT_PAGE, request);
    }

    private List<DiscoveryFollowUp> forDeclaration(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            DeclarationSubjectIdentity subject) {
        return switch (subject) {
            case ResolvedMethodDeclarationSubjectIdentity methodSubject ->
                    forConceptMethod(repositoryId, revision, methodSubject.target());
            case TypeDeclarationSubjectIdentity typeSubject -> forType(
                    repositoryId,
                    revision,
                    typeSubject.type());
            case UnresolvedMethodDeclarationSubjectIdentity methodSubject -> forType(
                    repositoryId,
                    revision,
                    methodSubject.owner());
            case FieldDeclarationSubjectIdentity fieldSubject -> forType(
                    repositoryId,
                    revision,
                    fieldSubject.field().ownerType());
        };
    }

    private List<DiscoveryFollowUp> forType(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceTypeIdentity sourceType) {
        GetTypeMembersRequest request = new GetTypeMembersRequest(
                repositoryId(repositoryId),
                revision(revision),
                Objects.requireNonNull(sourceType, "sourceType is required"),
                List.of(TypeMemberKind.METHOD, TypeMemberKind.FIELD),
                Optional.empty(),
                0,
                DEFAULT_LIMIT);
        return List.of(followUp(Operation.GET_TYPE_MEMBERS, request));
    }

    private static DiscoveryFollowUp followUp(
            Operation operation,
            DiscoveryFollowUp.RequestProjection request) {
        return new DiscoveryFollowUp(operation, operation.api(), request);
    }

    private DiscoveryFollowUp sourceSymbolRetry(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            SourceSymbolResolutionQuery original,
            SourceSymbolContext context,
            Optional<SyntaxPosition> position) {
        ResolveSourceSymbolRequest request = new ResolveSourceSymbolRequest(
                repositoryId(repositoryId),
                revision(revision),
                context,
                original.symbol(),
                position);
        return followUp(Operation.RESOLVE_SOURCE_SYMBOL, request);
    }

    private static String repositoryId(RepositoryId repositoryId) {
        return Objects.requireNonNull(repositoryId, "repositoryId is required").value();
    }

    private static String revision(RepositoryRevision revision) {
        return Objects.requireNonNull(revision, "revision is required").value();
    }
}
