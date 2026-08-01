package com.java.semantic.api;

import com.java.semantic.api.dto.AnnotationTypeResponse;
import com.java.semantic.api.dto.AnnotationTypeResponse.ResolvedAnnotationTypeResponse;
import com.java.semantic.api.dto.AnnotationTypeResponse.UnresolvedAnnotationTypeResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.ApiRouteConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.FieldConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementKeyResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementVariantConceptIdentityResponse;
import com.java.semantic.api.dto.ConceptIdentityResponse.MapperStatementVariantResponse;
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
import com.java.semantic.api.dto.JavaTypeIdentityResponse;
import com.java.semantic.api.dto.ReferencedTypeResponse;
import com.java.semantic.api.dto.TypeUsageLocationResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.TypeArgumentPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.TypeVariableBoundPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.WildcardExtendsBoundPathResponse;
import com.java.semantic.api.dto.TypeUsagePathResponse.WildcardSuperBoundPathResponse;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.SourceTypeIdentity;
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
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 將概念 identity 與欄位型別證據投影為封閉 HTTP 回應 */
@Component
public final class ConceptIdentityHttpMapper {

    /** 將任一概念 identity 投影為其精確 HTTP discriminator 變體 */
    public ConceptIdentityResponse toResponse(ConceptIdentity identity) {
        ConceptIdentity conceptIdentity = Objects.requireNonNull(identity, "identity is required");
        return switch (conceptIdentity) {
            case TypeConceptIdentity typeIdentity -> new TypeConceptIdentityResponse(
                    typeIdentity.identityKind().name(),
                    typeIdentity.type().sourceFile(),
                    typeIdentity.type().javaType().packageName(),
                    typeIdentity.type().javaType().className());
            case MethodConceptIdentity methodIdentity -> new MethodConceptIdentityResponse(
                    methodIdentity.identityKind().name(),
                    MethodTargetHttpMapper.toResponse(methodIdentity.target()));
            case FieldConceptIdentity fieldIdentity -> new FieldConceptIdentityResponse(
                    fieldIdentity.identityKind().name(),
                    fieldIdentity.field().ownerType().sourceFile(),
                    fieldIdentity.field().ownerType().javaType().packageName(),
                    fieldIdentity.field().ownerType().javaType().className(),
                    fieldIdentity.field().name());
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
                    MethodTargetHttpMapper.toResponse(routeIdentity.target()),
                    routeIdentity.httpVerb(),
                    routeIdentity.route());
            case MqDestinationConceptIdentity destinationIdentity -> new MqDestinationConceptIdentityResponse(
                    destinationIdentity.identityKind().name(),
                    MethodTargetHttpMapper.toResponse(destinationIdentity.target()),
                    destinationIdentity.broker().name(),
                    destinationIdentity.destination());
            case ScheduleConceptIdentity scheduleIdentity -> new ScheduleConceptIdentityResponse(
                    scheduleIdentity.identityKind().name(),
                    MethodTargetHttpMapper.toResponse(scheduleIdentity.target()),
                    scheduleIdentity.triggerKind().name(),
                    scheduleIdentity.triggerValue());
            case MapperStatementConceptIdentity mapperIdentity -> new MapperStatementConceptIdentityResponse(
                    mapperIdentity.identityKind().name(),
                    mapperStatementKey(mapperIdentity.statementKey().namespace(), mapperIdentity.statementKey().statementId()));
            case MapperStatementVariantEvidenceIdentity variantIdentity ->
                    new MapperStatementVariantConceptIdentityResponse(
                            variantIdentity.identityKind().name(),
                            new MapperStatementVariantResponse(
                                    mapperStatementKey(
                                            variantIdentity.mapperStatement().statementKey().namespace(),
                                            variantIdentity.mapperStatement().statementKey().statementId()),
                                    variantIdentity.mapperStatement().resourcePath(),
                                    variantIdentity.mapperStatement().databaseId(),
                                    variantIdentity.mapperStatement().documentOrdinal(),
                                    variantIdentity.mapperStatement().representation().name()));
        };
    }

    /** 將封閉 HTTP identity 還原為精確 domain identity，不做任何補全或搜尋 */
    public ConceptIdentity toDomain(ConceptIdentityResponse response) {
        ConceptIdentityResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case TypeConceptIdentityResponse type -> new TypeConceptIdentity(
                    sourceType(type.sourceFile(), type.packageName(), type.className()));
            case MethodConceptIdentityResponse method -> new MethodConceptIdentity(
                    MethodTargetHttpMapper.toDomain(method.target()));
            case FieldConceptIdentityResponse field -> new FieldConceptIdentity(
                    new TypeMember(sourceType(
                            field.sourceFile(), field.ownerPackageName(), field.ownerClassName()), field.fieldName()));
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
                    MethodTargetHttpMapper.toDomain(route.target()), route.httpVerb(), route.route());
            case MqDestinationConceptIdentityResponse destination -> new MqDestinationConceptIdentity(
                    MethodTargetHttpMapper.toDomain(destination.target()),
                    MqBroker.valueOf(destination.broker()),
                    destination.destination());
            case ScheduleConceptIdentityResponse schedule -> new ScheduleConceptIdentity(
                    MethodTargetHttpMapper.toDomain(schedule.target()),
                    ScheduleTriggerKind.valueOf(schedule.triggerKind()),
                    schedule.triggerValue());
            case MapperStatementConceptIdentityResponse mapper -> new MapperStatementConceptIdentity(
                    mapperStatementKey(mapper.statement()));
            case MapperStatementVariantConceptIdentityResponse variant ->
                    new MapperStatementVariantEvidenceIdentity(new MapperStatementIdentity(
                            mapperStatementKey(variant.variant().statement()),
                            variant.variant().resourcePath(),
                            variant.variant().databaseId(),
                            variant.variant().documentOrdinal(),
                            MapperEvidenceRepresentation.valueOf(variant.variant().representation())));
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
                    typeSubject.type().sourceFile(),
                    typeSubject.type().javaType().packageName(),
                    typeSubject.type().javaType().className());
            case ResolvedMethodDeclarationSubjectIdentity methodSubject ->
                    new ResolvedMethodDeclarationSubjectResponse(
                            "METHOD",
                            MethodTargetHttpMapper.toResponse(methodSubject.target()));
            case UnresolvedMethodDeclarationSubjectIdentity methodSubject ->
                    new UnresolvedMethodDeclarationSubjectResponse(
                            "METHOD_UNRESOLVED",
                            methodSubject.owner().sourceFile(),
                            methodSubject.owner().javaType().packageName(),
                            methodSubject.owner().javaType().className(),
                            methodSubject.signature().methodName(),
                            methodSubject.signature().parameterTypes());
            case FieldDeclarationSubjectIdentity fieldSubject -> new FieldDeclarationSubjectResponse(
                    "FIELD",
                    fieldSubject.field().ownerType().sourceFile(),
                    fieldSubject.field().ownerType().javaType().packageName(),
                    fieldSubject.field().ownerType().javaType().className(),
                    fieldSubject.field().name());
        };
    }

    private DeclarationSubjectIdentity declarationSubject(DeclarationSubjectResponse response) {
        DeclarationSubjectResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return switch (httpResponse) {
            case TypeDeclarationSubjectResponse type -> new TypeDeclarationSubjectIdentity(
                    sourceType(type.sourceFile(), type.packageName(), type.className()));
            case ResolvedMethodDeclarationSubjectResponse method ->
                    new ResolvedMethodDeclarationSubjectIdentity(MethodTargetHttpMapper.toDomain(method.target()));
            case UnresolvedMethodDeclarationSubjectResponse method ->
                    new UnresolvedMethodDeclarationSubjectIdentity(
                            sourceType(
                                    method.sourceFile(), method.ownerPackageName(), method.ownerClassName()),
                            new MethodDeclarationSignature(method.methodName(), method.parameterTypes()));
            case FieldDeclarationSubjectResponse field -> new FieldDeclarationSubjectIdentity(
                    new TypeMember(sourceType(
                            field.sourceFile(), field.ownerPackageName(), field.ownerClassName()), field.fieldName()));
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

    private MapperStatementKeyResponse mapperStatementKey(String namespace, String statementId) {
        return new MapperStatementKeyResponse(namespace, statementId);
    }

    private MapperStatementKey mapperStatementKey(MapperStatementKeyResponse response) {
        MapperStatementKeyResponse httpResponse = Objects.requireNonNull(response, "response is required");
        return new MapperStatementKey(httpResponse.namespace(), httpResponse.statementId());
    }

    private JavaTypeIdentityResponse javaType(JavaTypeIdentity javaType) {
        return new JavaTypeIdentityResponse(javaType.packageName(), javaType.className());
    }

    private JavaTypeIdentity javaType(JavaTypeIdentityResponse response) {
        JavaTypeIdentityResponse typeResponse = Objects.requireNonNull(response, "response is required");
        return new JavaTypeIdentity(typeResponse.packageName(), typeResponse.className());
    }

    private SourceTypeIdentity sourceType(String sourceFile, String packageName, String className) {
        return new SourceTypeIdentity(new JavaTypeIdentity(packageName, className), sourceFile);
    }
}
