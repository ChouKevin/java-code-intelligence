package com.java.semantic.api;

import com.java.semantic.api.dto.AnnotationTypeResponse;
import com.java.semantic.api.dto.AnnotationTypeResponse.ResolvedAnnotationTypeResponse;
import com.java.semantic.api.dto.AnnotationTypeResponse.UnresolvedAnnotationTypeResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.ApiRouteConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.FieldConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementVariantConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MethodConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MqDestinationConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.ScheduleConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.TypeConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.TypeUsageConceptIdentityResponse;
import com.java.semantic.api.dto.DeclarationSubjectResponse;
import com.java.semantic.api.dto.DeclarationSubjectResponse.FieldDeclarationSubjectResponse;
import com.java.semantic.api.dto.DeclarationSubjectResponse.ResolvedMethodDeclarationSubjectResponse;
import com.java.semantic.api.dto.DeclarationSubjectResponse.TypeDeclarationSubjectResponse;
import com.java.semantic.api.dto.DeclarationSubjectResponse.UnresolvedMethodDeclarationSubjectResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.ArrayFieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.NamedFieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.ParameterizedFieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.PrimitiveFieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.TypeVariableFieldTypeReferenceResponse;
import com.java.semantic.api.dto.FieldTypeReferenceResponse.WildcardFieldTypeReferenceResponse;
import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;
import com.java.semantic.api.dto.ReferencedTypeResponse;
import com.java.semantic.api.dto.TypeUsageLocationResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.TypeArgumentPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.TypeVariableBoundPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.WildcardExtendsBoundPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.WildcardSuperBoundPathResponse;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.FieldConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.TypeConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.MqDestinationConceptIdentity;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ScheduleConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.application.concept.MethodDeclarationSignature;
import com.java.semantic.syntax.application.concept.ReferencedTypeIdentity;
import com.java.semantic.syntax.application.concept.TypeUsagePath;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageSlot;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.CompositeTypeReference;
import com.java.semantic.syntax.domain.InferredTypeReference;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.PrimitiveTypeReference;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 將概念 identity 與欄位型別證據投影為封閉 HTTP 回應 */
@Component
public final class ConceptIdentityHttpMapper {

    private final SourceLocationHttpMapper sourceLocationMapper;
    private final MapperIdentityHttpMapper mapperIdentityHttpMapper;

    public ConceptIdentityHttpMapper(
            SourceLocationHttpMapper sourceLocationMapper,
            MapperIdentityHttpMapper mapperIdentityHttpMapper) {
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.mapperIdentityHttpMapper = Objects.requireNonNull(
                mapperIdentityHttpMapper, "mapperIdentityHttpMapper is required");
    }

    /** 將任一概念 identity 投影為其精確 HTTP discriminator 變體 */
    public ConceptIdentityResponse toResponse(ConceptIdentity identity) {
        ConceptIdentity conceptIdentity = Objects.requireNonNull(identity, "identity is required");
        return switch (conceptIdentity) {
            case TypeConceptIdentity typeIdentity -> new TypeConceptIdentityResponse(
                    typeIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(typeIdentity.type()));
            case MethodConceptIdentity methodIdentity -> new MethodConceptIdentityResponse(
                    methodIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(methodIdentity.target()));
            case FieldConceptIdentity fieldIdentity -> new FieldConceptIdentityResponse(
                    fieldIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(fieldIdentity.field(), sourceLocationMapper));
            case AnnotationUsageConceptIdentity annotationIdentity ->
                    new ConceptIdentityResponse.AnnotationUsageConceptIdentityResponse(
                            annotationIdentity.identityKind().name(),
                            declarationSubject(annotationIdentity.annotatedDeclaration()),
                            annotationType(annotationIdentity.annotationIdentity()));
            case TypeUsageConceptIdentity typeUsageIdentity -> new TypeUsageConceptIdentityResponse(
                    typeUsageIdentity.identityKind().name(),
                    declarationSubject(typeUsageIdentity.owner()),
                    new TypeUsageLocationResponse(
                            typeUsageIdentity.location().slot().name(),
                            typeUsageIdentity.location().index()),
                    typeUsageIdentity.path().stream().map(this::typeUsagePath).toList(),
                    referencedType(typeUsageIdentity.referencedType()));
            case ApiRouteConceptIdentity routeIdentity -> new ApiRouteConceptIdentityResponse(
                    routeIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(routeIdentity.target()),
                    routeIdentity.httpVerb(),
                    routeIdentity.route());
            case MqDestinationConceptIdentity destinationIdentity -> new MqDestinationConceptIdentityResponse(
                    destinationIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(destinationIdentity.target()),
                    destinationIdentity.broker().name(),
                    destinationIdentity.destination());
            case ScheduleConceptIdentity scheduleIdentity -> new ScheduleConceptIdentityResponse(
                    scheduleIdentity.identityKind().name(),
                    JavaSourceIdentityHttpMapper.toPayload(scheduleIdentity.target()),
                    scheduleIdentity.triggerKind().name(),
                    scheduleIdentity.triggerValue());
            case MapperStatementConceptIdentity mapperIdentity -> new MapperStatementConceptIdentityResponse(
                    mapperIdentity.identityKind().name(),
                    mapperIdentityHttpMapper.toPayload(mapperIdentity.statementKey()));
            case MapperStatementVariantEvidenceIdentity variantIdentity ->
                    new MapperStatementVariantConceptIdentityResponse(
                            variantIdentity.identityKind().name(),
                            mapperIdentityHttpMapper.toPayload(variantIdentity.mapperStatement()));
        };
    }

    /** 將封閉 HTTP identity 還原為精確 domain identity，不做任何補全或搜尋 */
    public ConceptIdentity toDomain(ConceptIdentityResponse response) {
        ConceptIdentityResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case TypeConceptIdentityResponse type -> new TypeConceptIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(type.sourceType()));
            case MethodConceptIdentityResponse method -> new MethodConceptIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(method.target()));
            case FieldConceptIdentityResponse field -> new FieldConceptIdentity(
                    requireTypeMember(JavaSourceIdentityHttpMapper.toDomain(field.identity(), sourceLocationMapper)));
            case ConceptIdentityResponse.AnnotationUsageConceptIdentityResponse annotation ->
                    new AnnotationUsageConceptIdentity(
                            declarationSubject(annotation.declaration()),
                            annotationType(annotation.annotationType()));
            case TypeUsageConceptIdentityResponse typeUsage -> new TypeUsageConceptIdentity(
                    declarationSubject(typeUsage.owner()),
                    new TypeUsageLocation(
                            TypeUsageSlot.valueOf(typeUsage.location().slot()),
                            typeUsage.location().index()),
                    typeUsage.path().stream().map(this::typeUsagePath).toList(),
                    new ReferencedTypeIdentity(
                            javaType(typeUsage.referencedType().javaType()),
                            typeUsage.referencedType().arrayDimensions()));
            case ApiRouteConceptIdentityResponse route -> new ApiRouteConceptIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(route.target()), route.httpVerb(), route.route());
            case MqDestinationConceptIdentityResponse destination -> new MqDestinationConceptIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(destination.target()),
                    MqBroker.valueOf(destination.broker()),
                    destination.destination());
            case ScheduleConceptIdentityResponse schedule -> new ScheduleConceptIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(schedule.target()),
                    ScheduleTriggerKind.valueOf(schedule.triggerKind()),
                    schedule.triggerValue());
            case MapperStatementConceptIdentityResponse mapper -> new MapperStatementConceptIdentity(
                    mapperIdentityHttpMapper.toDomain(mapper.identity()));
            case MapperStatementVariantConceptIdentityResponse variant ->
                    new MapperStatementVariantEvidenceIdentity(
                            mapperIdentityHttpMapper.toDomain(variant.identity()));
        };
    }

    /** 將欄位宣告可達型別證據投影為封閉遞迴回應 */
    public FieldTypeReferenceResponse fieldTypeReference(TypeReference reference) {
        TypeReference typeReference = Objects.requireNonNull(reference, "reference is required");
        return switch (typeReference) {
            case NamedTypeReference namedType -> new NamedFieldTypeReferenceResponse(
                    "NAMED",
                    namedType.writtenType(),
                    namedType.simpleTypeName(),
                    namedType.resolvedNamedType().map(this::javaType),
                    namedType.sourceDefined());
            case ParameterizedTypeReference parameterizedType -> new ParameterizedFieldTypeReferenceResponse(
                    "PARAMETERIZED",
                    parameterizedType.writtenType(),
                    namedType(parameterizedType.rawType()),
                    parameterizedType.typeArguments().stream().map(this::fieldTypeReference).toList());
            case PrimitiveTypeReference primitiveType -> new PrimitiveFieldTypeReferenceResponse(
                    "PRIMITIVE",
                    primitiveType.writtenType());
            case ArrayTypeReference arrayType -> new ArrayFieldTypeReferenceResponse(
                    "ARRAY",
                    arrayType.writtenType(),
                    fieldTypeReference(arrayType.elementType()),
                    arrayType.dimensions());
            case WildcardTypeReference wildcardType -> new WildcardFieldTypeReferenceResponse(
                    "WILDCARD",
                    wildcardType.writtenType(),
                    wildcardType.upperBound().map(this::fieldTypeReference),
                    wildcardType.lowerBound().map(this::fieldTypeReference),
                    wildcardType.sourceDefined());
            case TypeVariableReference typeVariable -> new TypeVariableFieldTypeReferenceResponse(
                    "TYPE_VARIABLE",
                    typeVariable.writtenType(),
                    typeVariable.variableName(),
                    typeVariable.upperBounds().stream().map(this::fieldTypeReference).toList(),
                    typeVariable.sourceDefined());
            case CompositeTypeReference compositeType -> throw new IllegalArgumentException(
                    "COMPOSITE type references cannot occur in field details: " + compositeType.writtenType());
            case InferredTypeReference inferredType -> throw new IllegalArgumentException(
                    "INFERRED type references cannot occur in field details: " + inferredType.writtenType());
        };
    }

    private DeclarationSubjectResponse declarationSubject(DeclarationSubjectIdentity subject) {
        return switch (subject) {
            case TypeDeclarationSubjectIdentity typeSubject -> new TypeDeclarationSubjectResponse(
                    "TYPE",
                    JavaSourceIdentityHttpMapper.toPayload(typeSubject.type()));
            case ResolvedMethodDeclarationSubjectIdentity methodSubject ->
                    new ResolvedMethodDeclarationSubjectResponse(
                            "METHOD",
                            JavaSourceIdentityHttpMapper.toPayload(methodSubject.target()));
            case UnresolvedMethodDeclarationSubjectIdentity methodSubject ->
                    new UnresolvedMethodDeclarationSubjectResponse(
                            "METHOD_UNRESOLVED",
                            JavaSourceIdentityHttpMapper.toPayload(new MethodTarget(
                                    methodSubject.owner(),
                                    methodSubject.signature().methodName(),
                                    methodSubject.signature().parameterTypes())));
            case FieldDeclarationSubjectIdentity fieldSubject -> new FieldDeclarationSubjectResponse(
                    "FIELD",
                    JavaSourceIdentityHttpMapper.toPayload(fieldSubject.field(), sourceLocationMapper));
        };
    }

    private DeclarationSubjectIdentity declarationSubject(DeclarationSubjectResponse response) {
        DeclarationSubjectResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case TypeDeclarationSubjectResponse type -> new TypeDeclarationSubjectIdentity(
                    JavaSourceIdentityHttpMapper.toDomain(type.sourceType()));
            case ResolvedMethodDeclarationSubjectResponse method ->
                    new ResolvedMethodDeclarationSubjectIdentity(JavaSourceIdentityHttpMapper.toDomain(method.target()));
            case UnresolvedMethodDeclarationSubjectResponse method ->
                    new UnresolvedMethodDeclarationSubjectIdentity(
                            JavaSourceIdentityHttpMapper.toDomain(method.target().sourceType()),
                            new MethodDeclarationSignature(
                                    method.target().methodName(), method.target().parameterTypes()));
            case FieldDeclarationSubjectResponse field -> new FieldDeclarationSubjectIdentity(
                    requireTypeMember(JavaSourceIdentityHttpMapper.toDomain(field.identity(), sourceLocationMapper)));
        };
    }

    private AnnotationTypeResponse annotationType(AnnotationIdentity identity) {
        return switch (identity) {
            case ResolvedAnnotationIdentity resolvedIdentity -> new ResolvedAnnotationTypeResponse(
                    "RESOLVED",
                    javaType(resolvedIdentity.javaType()));
            case UnresolvedAnnotationIdentity unresolvedIdentity -> new UnresolvedAnnotationTypeResponse(
                    "UNRESOLVED",
                    unresolvedIdentity.writtenName());
        };
    }

    private AnnotationIdentity annotationType(AnnotationTypeResponse response) {
        AnnotationTypeResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case ResolvedAnnotationTypeResponse annotation -> new ResolvedAnnotationIdentity(
                    javaType(annotation.javaType()));
            case UnresolvedAnnotationTypeResponse annotation -> new UnresolvedAnnotationIdentity(
                    annotation.writtenName());
        };
    }

    private ReferencedTypeResponse referencedType(ReferencedTypeIdentity identity) {
        return new ReferencedTypeResponse(javaType(identity.javaType()), identity.arrayDimensions());
    }

    private TypeUsagePathResponse typeUsagePath(TypeUsagePath path) {
        return switch (path) {
            case TypeUsagePath.TypeArgument argument -> new TypeArgumentPathResponse("TYPE_ARGUMENT", argument.index());
            case TypeUsagePath.WildcardExtendsBound ignored -> new WildcardExtendsBoundPathResponse("WILDCARD_EXTENDS_BOUND");
            case TypeUsagePath.WildcardSuperBound ignored -> new WildcardSuperBoundPathResponse("WILDCARD_SUPER_BOUND");
            case TypeUsagePath.TypeVariableBound bound -> new TypeVariableBoundPathResponse("TYPE_VARIABLE_BOUND", bound.index());
        };
    }

    private TypeUsagePath typeUsagePath(TypeUsagePathResponse response) {
        TypeUsagePathResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case TypeArgumentPathResponse argument -> new TypeUsagePath.TypeArgument(argument.index());
            case WildcardExtendsBoundPathResponse ignored -> new TypeUsagePath.WildcardExtendsBound();
            case WildcardSuperBoundPathResponse ignored -> new TypeUsagePath.WildcardSuperBound();
            case TypeVariableBoundPathResponse bound -> new TypeUsagePath.TypeVariableBound(bound.index());
        };
    }

    private NamedFieldTypeReferenceResponse namedType(NamedTypeReference namedType) {
        return new NamedFieldTypeReferenceResponse(
                "NAMED",
                namedType.writtenType(),
                namedType.simpleTypeName(),
                namedType.resolvedNamedType().map(this::javaType),
                namedType.sourceDefined());
    }

    private JavaTypeIdentityPayload javaType(JavaTypeIdentity javaType) {
        return JavaSourceIdentityHttpMapper.toPayload(javaType);
    }

    private JavaTypeIdentity javaType(JavaTypeIdentityPayload response) {
        return JavaSourceIdentityHttpMapper.toDomain(response);
    }

    private TypeMember requireTypeMember(SourceMemberIdentity identity) {
        SourceMemberIdentity member = Objects.requireNonNull(identity, "identity is required");
        if (member instanceof TypeMember typeMember) {
            return typeMember;
        }
        throw new IllegalArgumentException("field identity must be type-scoped");
    }
}
