package com.java.semantic.mcp.mapper;

import com.java.semantic.mcp.dto.concept.ConceptDiscoveryMcpDtos;
import com.java.semantic.mcp.dto.concept.McpConceptIdentityPayload;
import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads;
import com.java.semantic.mcp.dto.identity.McpMapperIdentityPayloads;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.ConceptSearchResult;
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
import com.java.semantic.syntax.application.concept.RevisionBoundConceptResolution;
import com.java.semantic.syntax.application.concept.TypeUsagePath;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.AnnotationUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.DeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.FieldDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeDeclarationSubjectIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageConceptIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.TypeUsageLocation;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedAnnotationIdentity;
import com.java.semantic.syntax.application.concept.UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 將 concept discovery 結果投影為 MCP transport DTO */
@Component
public final class ConceptDiscoveryMcpMapper {

    /** 將封閉 MCP identity 還原為既有 concept domain identity */
    public ConceptIdentity toDomain(McpConceptIdentityPayload payload) {
        McpConceptIdentityPayload identity = Objects.requireNonNull(payload, "payload is required");
        return switch (identity) {
            case McpConceptIdentityPayload.TypeIdentity type -> new TypeConceptIdentity(
                    McpJavaIdentityPayloads.toDomain(type.sourceType()));
            case McpConceptIdentityPayload.MethodIdentity method -> new MethodConceptIdentity(
                    McpJavaIdentityPayloads.toDomain(method.target()));
            case McpConceptIdentityPayload.FieldIdentity field -> new FieldConceptIdentity(
                    requireTypeMember(McpJavaIdentityPayloads.toDomain(field.field())));
            case McpConceptIdentityPayload.AnnotationUsageIdentity annotation -> new AnnotationUsageConceptIdentity(
                    declarationSubject(annotation.declaration()), annotation(annotation.annotationType()));
            case McpConceptIdentityPayload.TypeUsageIdentity typeUsage -> new TypeUsageConceptIdentity(
                    declarationSubject(typeUsage.owner()),
                    new TypeUsageLocation(typeUsage.location().slot(), typeUsage.location().index()),
                    typeUsage.path().stream().map(this::typeUsagePath).toList(),
                    new ReferencedTypeIdentity(
                            McpJavaIdentityPayloads.toDomain(typeUsage.referencedType().javaType()),
                            typeUsage.referencedType().arrayDimensions()));
            case McpConceptIdentityPayload.ApiRouteIdentity route -> new ApiRouteConceptIdentity(
                    McpJavaIdentityPayloads.toDomain(route.target()), route.httpVerb(), route.route());
            case McpConceptIdentityPayload.MqDestinationIdentity destination -> new MqDestinationConceptIdentity(
                    McpJavaIdentityPayloads.toDomain(destination.target()), destination.broker(), destination.destination());
            case McpConceptIdentityPayload.ScheduleIdentity schedule -> new ScheduleConceptIdentity(
                    McpJavaIdentityPayloads.toDomain(schedule.target()), schedule.triggerKind(), schedule.triggerValue());
            case McpConceptIdentityPayload.MapperStatementIdentity mapper -> new MapperStatementConceptIdentity(
                    McpMapperIdentityPayloads.toDomain(mapper.identity()));
            case McpConceptIdentityPayload.MapperStatementVariantIdentity variant ->
                    new MapperStatementVariantEvidenceIdentity(McpMapperIdentityPayloads.toDomain(variant.identity()));
        };
    }

    public ConceptDiscoveryMcpDtos.DiscoverOutput discover(ConceptSearchResult result) {
        return new ConceptDiscoveryMcpDtos.DiscoverOutput(result);
    }

    public ConceptDiscoveryMcpDtos.ResolveOutput resolve(RevisionBoundConceptResolution result) {
        return new ConceptDiscoveryMcpDtos.ResolveOutput(result);
    }

    public ConceptDiscoveryMcpDtos.TypeMembersOutput typeMembers(TypeMemberResult result) {
        return new ConceptDiscoveryMcpDtos.TypeMembersOutput(result);
    }

    private DeclarationSubjectIdentity declarationSubject(McpConceptIdentityPayload.DeclarationSubject payload) {
        return switch (Objects.requireNonNull(payload, "payload is required")) {
            case McpConceptIdentityPayload.DeclarationSubject.Type type -> new TypeDeclarationSubjectIdentity(
                    McpJavaIdentityPayloads.toDomain(type.sourceType()));
            case McpConceptIdentityPayload.DeclarationSubject.Method method ->
                    new ResolvedMethodDeclarationSubjectIdentity(McpJavaIdentityPayloads.toDomain(method.target()));
            case McpConceptIdentityPayload.DeclarationSubject.UnresolvedMethod method ->
                    new UnresolvedMethodDeclarationSubjectIdentity(
                            McpJavaIdentityPayloads.toDomain(method.owner()),
                            new MethodDeclarationSignature(method.methodName(), method.parameterTypes()));
            case McpConceptIdentityPayload.DeclarationSubject.Field field -> new FieldDeclarationSubjectIdentity(
                    requireTypeMember(McpJavaIdentityPayloads.toDomain(field.field())));
        };
    }

    private AnnotationIdentity annotation(McpConceptIdentityPayload.Annotation payload) {
        return switch (Objects.requireNonNull(payload, "payload is required")) {
            case McpConceptIdentityPayload.Annotation.Resolved resolved -> new ResolvedAnnotationIdentity(
                    McpJavaIdentityPayloads.toDomain(resolved.javaType()));
            case McpConceptIdentityPayload.Annotation.Unresolved unresolved -> new UnresolvedAnnotationIdentity(
                    unresolved.writtenName());
        };
    }

    private TypeUsagePath typeUsagePath(McpConceptIdentityPayload.TypeUsagePath payload) {
        return switch (Objects.requireNonNull(payload, "payload is required")) {
            case McpConceptIdentityPayload.TypeUsagePath.TypeArgument argument ->
                    new TypeUsagePath.TypeArgument(argument.index());
            case McpConceptIdentityPayload.TypeUsagePath.WildcardExtendsBound ignored ->
                    new TypeUsagePath.WildcardExtendsBound();
            case McpConceptIdentityPayload.TypeUsagePath.WildcardSuperBound ignored ->
                    new TypeUsagePath.WildcardSuperBound();
            case McpConceptIdentityPayload.TypeUsagePath.TypeVariableBound bound ->
                    new TypeUsagePath.TypeVariableBound(bound.index());
        };
    }

    private static SourceMemberIdentity.TypeMember requireTypeMember(SourceMemberIdentity identity) {
        if (identity instanceof SourceMemberIdentity.TypeMember typeMember) {
            return typeMember;
        }
        throw new IllegalArgumentException("concept field identity must be a type member");
    }
}
