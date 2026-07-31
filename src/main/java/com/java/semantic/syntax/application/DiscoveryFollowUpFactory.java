package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.application.ConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.DeclarationSubjectIdentity;
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
import com.java.semantic.syntax.application.DiscoveryFollowUp.AnalyzeCallGraphRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ConceptDiscoveryRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.ConceptTermRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.DiscoverMethodImplementationsRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMethodSourceRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetMapperStatementRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.GetTypeMembersRequest;
import com.java.semantic.syntax.application.DiscoveryFollowUp.Operation;
import com.java.semantic.syntax.application.DiscoveryFollowUp.TypeMembersRequest;

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
                    typeIdentity.sourceFile(),
                    typeIdentity.fullyQualifiedType());
            case MethodConceptIdentity methodIdentity ->
                    forConceptMethod(repositoryId, revision, methodIdentity.target());
            case FieldConceptIdentity fieldIdentity -> forType(
                    repositoryId,
                    revision,
                    fieldIdentity.sourceFile(),
                    fieldIdentity.ownerType());
            case AnnotationUsageConceptIdentity annotationIdentity -> forDeclaration(
                    repositoryId,
                    revision,
                    annotationIdentity.annotatedDeclaration());
            case TypeUsageConceptIdentity typeUsageIdentity -> forDeclaration(
                    repositoryId,
                    revision,
                    typeUsageIdentity.ownerDeclaration());
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
     * 已解析欄位型別使用 canonical exact TYPE 搜尋
     * 外部 dependency 可以合法回傳零筆且不推測來源檔
     */
    public List<DiscoveryFollowUp> forResolvedFieldType(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            Optional<String> resolvedType) {
        Optional<String> type = Objects.requireNonNull(resolvedType, "resolvedType is required");
        if (!type.isPresent()) {
            return List.of();
        }
        ConceptDiscoveryRequest request = new ConceptDiscoveryRequest(
                repositoryId(repositoryId),
                revision(revision),
                "ALL",
                List.of(new ConceptTermRequest(type.orElseThrow(), ConceptMatchMode.CANONICAL_EXACT)),
                List.of(ConceptKind.TYPE),
                Optional.empty(),
                0,
                50);
        return List.of(followUp(Operation.DISCOVER_CONCEPTS, request));
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
                nextQuery.sourceFile(),
                nextQuery.fullyQualifiedName(),
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
                    typeSubject.sourceFile(),
                    typeSubject.ownerType());
            case UnresolvedMethodDeclarationSubjectIdentity methodSubject -> forType(
                    repositoryId,
                    revision,
                    methodSubject.sourceFile(),
                    methodSubject.ownerType());
            case FieldDeclarationSubjectIdentity fieldSubject -> forType(
                    repositoryId,
                    revision,
                    fieldSubject.sourceFile(),
                    fieldSubject.ownerType());
        };
    }

    private List<DiscoveryFollowUp> forType(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            String sourceFile,
            String fullyQualifiedName) {
        GetTypeMembersRequest request = new GetTypeMembersRequest(
                repositoryId(repositoryId),
                revision(revision),
                sourceFile,
                fullyQualifiedName,
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

    private static String repositoryId(RepositoryId repositoryId) {
        return Objects.requireNonNull(repositoryId, "repositoryId is required").value();
    }

    private static String revision(RepositoryRevision revision) {
        return Objects.requireNonNull(revision, "revision is required").value();
    }
}
