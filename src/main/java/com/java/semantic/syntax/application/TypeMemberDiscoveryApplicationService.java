package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.concept.ConceptPage;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceEnumConstantMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceRecordComponentMetadata;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.syntax.domain.MethodImplementationEligibilityPolicy;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 在固定儲存庫快照內依來源限定型別 identity 建立一個合併成員頁 */
public final class TypeMemberDiscoveryApplicationService {

    private static final Comparator<MethodTypeMember> METHOD_ORDER = Comparator
            .comparing((MethodTypeMember member) -> member.target().methodName())
            .thenComparing(member -> member.target().parameterTypes(),
                    TypeMemberDiscoveryApplicationService::compareParameterSignatures);

    private static final Comparator<FieldTypeMember> FIELD_ORDER = Comparator
            .comparing(FieldTypeMember::fieldName)
            .thenComparing(FieldTypeMember::writtenType);

    private static final Comparator<EnumConstantTypeMember> ENUM_CONSTANT_ORDER = Comparator
            .comparing(EnumConstantTypeMember::constantName);

    private static final Comparator<RecordComponentTypeMember> RECORD_COMPONENT_ORDER = Comparator
            .comparing(RecordComponentTypeMember::componentName)
            .thenComparing(RecordComponentTypeMember::writtenType);

    private final RepositoryApplicationService repositoryApplicationService;
    private final RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    private final DiscoveryFollowUpFactory followUpFactory;

    public TypeMemberDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            DiscoveryFollowUpFactory followUpFactory) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.repositorySyntaxProvider = Objects.requireNonNull(
                repositorySyntaxProvider, "repositorySyntaxProvider is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
    }

    /** 在 expectedRevision 讀鎖內重新抽取 syntax 並套用一次合併 page window */
    public TypeMemberResult discover(TypeMemberQuery query) {
        TypeMemberQuery memberQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                memberQuery.repositoryId(),
                Optional.of(memberQuery.expectedRevision()),
                snapshot -> discoverSnapshot(snapshot, memberQuery));
    }

    private TypeMemberResult discoverSnapshot(RepositorySnapshot snapshot, TypeMemberQuery query) {
        RepositorySyntax syntax = repositorySyntaxProvider.get(snapshot);
        SourceTypeMetadata metadata = resolveType(syntax, query);
        List<TypeMember> combined = combinedMembers(snapshot, syntax, metadata, query);
        int start = Math.min(query.offset(), combined.size());
        int end = (int) Math.min((long) start + query.limit(), combined.size());
        List<TypeMember> members = List.copyOf(combined.subList(start, end));
        boolean hasMore = end < combined.size();
        ConceptPage page = new ConceptPage(
                query.offset(), query.limit(), members.size(), combined.size(), hasMore);
        List<DiscoveryFollowUp> followUps = hasMore
                ? List.of(followUpFactory.nextTypeMemberPage(query, end))
                : List.of();
        return new TypeMemberResult(
                snapshot.repositoryId(),
                snapshot.revision(),
                metadata.declaration().identity(),
                metadata.declaration().kind(),
                metadata.frameworkFacts().annotations().stream().map(AnnotationEvidence::writtenName).toList(),
                metadata.relationships().implementedTypes().stream().map(reference -> reference.simpleTypeName()).toList(),
                metadata.relationships().extendedTypes().stream().map(reference -> reference.simpleTypeName()).toList(),
                members,
                page,
                syntax.extractionOutcomes(),
                followUps);
    }

    private List<TypeMember> combinedMembers(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            SourceTypeMetadata metadata,
            TypeMemberQuery query) {
        List<TypeMember> combined = new ArrayList<>();
        if (query.memberKinds().contains(TypeMemberKind.METHOD)) {
            List<MethodTypeMember> methods = metadata.members().methods().stream()
                    .flatMap(method -> method.analysisTarget().target().stream()
                            .map(target -> methodMember(snapshot, metadata, method, target)))
                    .filter(member -> matchesPrefix(member.target().methodName(), query.namePrefix()))
                    .sorted(METHOD_ORDER)
                    .toList();
            combined.addAll(methods);
        }
        if (query.memberKinds().contains(TypeMemberKind.FIELD)) {
            List<FieldTypeMember> fields = metadata.members().fields().stream()
                    .map(field -> fieldMember(snapshot, syntax, metadata.declaration().identity(), field))
                    .filter(member -> matchesPrefix(member.fieldName(), query.namePrefix()))
                    .sorted(FIELD_ORDER)
                    .toList();
            combined.addAll(fields);
        }
        if (query.memberKinds().contains(TypeMemberKind.ENUM_CONSTANT)) {
            List<EnumConstantTypeMember> constants = metadata.members().enumConstants().stream()
                    .map(constant -> enumConstantMember(snapshot, metadata.declaration().identity(), constant))
                    .filter(member -> matchesPrefix(member.constantName(), query.namePrefix()))
                    .sorted(ENUM_CONSTANT_ORDER)
                    .toList();
            combined.addAll(constants);
        }
        if (query.memberKinds().contains(TypeMemberKind.RECORD_COMPONENT)) {
            List<RecordComponentTypeMember> components = metadata.members().recordComponents().stream()
                    .map(component -> recordComponentMember(snapshot, syntax, metadata.declaration().identity(), component))
                    .filter(member -> matchesPrefix(member.componentName(), query.namePrefix()))
                    .sorted(RECORD_COMPONENT_ORDER)
                    .toList();
            combined.addAll(components);
        }
        return List.copyOf(combined);
    }

    private EnumConstantTypeMember enumConstantMember(
            RepositorySnapshot snapshot,
            SourceTypeIdentity ownerType,
            SourceEnumConstantMetadata constant) {
        return new EnumConstantTypeMember(
                constant.name(),
                constant.declarationLocation(),
                constant.annotationEvidence().stream().map(AnnotationEvidence::writtenName).toList(),
                List.of(followUpFactory.internalSourceReferences(
                        snapshot.repositoryId(),
                        snapshot.revision(),
                        new SourceMemberIdentity.TypeMember(ownerType, constant.name()))));
    }

    private RecordComponentTypeMember recordComponentMember(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            SourceTypeIdentity ownerType,
            SourceRecordComponentMetadata component) {
        Optional<String> resolvedType = component.typeReference().resolvedTypeName();
        List<DiscoveryFollowUp> resolvedTypeFollowUps = followUpFactory.forResolvedFieldType(
                snapshot.repositoryId(), snapshot.revision(), sourceTypeIdentity(syntax, resolvedType));
        DiscoveryFollowUp selfReferences = followUpFactory.internalSourceReferences(
                snapshot.repositoryId(), snapshot.revision(),
                new SourceMemberIdentity.TypeMember(ownerType, component.name()));
        return new RecordComponentTypeMember(
                component.name(),
                component.typeReference().writtenType(),
                resolvedType,
                component.declarationLocation(),
                component.annotationEvidence().stream().map(AnnotationEvidence::writtenName).toList(),
                Stream.concat(resolvedTypeFollowUps.stream(), Stream.of(selfReferences)).toList());
    }

    private MethodTypeMember methodMember(
            RepositorySnapshot snapshot,
            SourceTypeMetadata metadata,
            SourceMethodMetadata declaration,
            MethodTarget target) {
        assertTargetBelongsToType(metadata, target);
        List<DiscoveryFollowUp> navigation = followUpFactory.forMethod(
                snapshot.repositoryId(), snapshot.revision(), target);
        List<DiscoveryFollowUp> followUps = MethodImplementationEligibilityPolicy.isEligible(metadata, declaration)
                ? Stream.concat(navigation.stream(), Stream.of(followUpFactory.forMethodImplementations(
                        snapshot.repositoryId(), snapshot.revision(), target))).toList()
                : navigation;
        return new MethodTypeMember(
                target,
                followUps);
    }

    private FieldTypeMember fieldMember(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            SourceTypeIdentity ownerType,
            SourceFieldMetadata field) {
        Optional<String> resolvedType = field.typeReference().resolvedTypeName();
        return new FieldTypeMember(
                field.name(),
                field.typeReference().writtenType(),
                resolvedType,
                field.annotationEvidence().stream().map(AnnotationEvidence::writtenName).toList(),
                List.of(TypeMemberLimitation.FIELD_USAGE_NOT_INDEXED),
                fieldFollowUps(snapshot, syntax, ownerType, field, resolvedType));
    }

    private List<DiscoveryFollowUp> fieldFollowUps(
            RepositorySnapshot snapshot,
            RepositorySyntax syntax,
            SourceTypeIdentity ownerType,
            SourceFieldMetadata field,
            Optional<String> resolvedType) {
        List<DiscoveryFollowUp> resolvedTypeFollowUps = followUpFactory.forResolvedFieldType(
                snapshot.repositoryId(), snapshot.revision(), sourceTypeIdentity(syntax, resolvedType));
        DiscoveryFollowUp selfReferences = followUpFactory.internalSourceReferences(
                snapshot.repositoryId(),
                snapshot.revision(),
                new SourceMemberIdentity.TypeMember(ownerType, field.name()));
        return Stream.concat(resolvedTypeFollowUps.stream(), Stream.of(selfReferences))
                .toList();
    }

    private static Optional<TypeConceptIdentity> sourceTypeIdentity(
            RepositorySyntax syntax,
            Optional<String> resolvedType) {
        Optional<String> typeName = Objects.requireNonNull(resolvedType, "resolvedType is required")
                .map(TypeMemberDiscoveryApplicationService::elementTypeName);
        return typeName.flatMap(name -> {
            List<TypeConceptIdentity> identities = syntax.sourceTypes().stream()
                    .map(metadata -> metadata.declaration().identity())
                    .filter(identity -> identity.fullyQualifiedName().equals(name))
                    .map(TypeConceptIdentity::new)
                    .toList();
            return identities.size() == 1 ? Optional.of(identities.getFirst()) : Optional.empty();
        });
    }

    private static String elementTypeName(String typeName) {
        String elementType = typeName;
        while (elementType.endsWith("[]")) {
            elementType = elementType.substring(0, elementType.length() - 2);
        }
        return elementType;
    }

    private static SourceTypeMetadata resolveType(RepositorySyntax syntax, TypeMemberQuery query) {
        List<SourceTypeMetadata> matches = syntax.sourceTypes().stream()
                .filter(metadata -> metadata.declaration().identity().equals(query.sourceType()))
                .toList();
        if (matches.size() < 1) {
            throw new TypeMemberTypeNotFoundException();
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("source-qualified type identity is duplicated");
        }
        return matches.getFirst();
    }

    private static void assertTargetBelongsToType(SourceTypeMetadata metadata, MethodTarget target) {
        boolean sameIdentity = target.sourceFile().equals(metadata.declaration().identity().sourceFile())
                && target.packageName().equals(metadata.declaration().identity().javaType().packageName())
                && target.className().equals(metadata.declaration().identity().javaType().className());
        if (!sameIdentity) {
            throw new IllegalStateException("resolved method target does not belong to requested type");
        }
    }

    private static boolean matchesPrefix(String name, Optional<String> namePrefix) {
        return namePrefix.map(name::startsWith).orElse(true);
    }

    private static int compareParameterSignatures(List<String> left, List<String> right) {
        int commonLength = Math.min(left.size(), right.size());
        for (int index = 0; index < commonLength; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
