package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import org.springframework.util.Assert;

/**
 * 概念唯一識別，等值比較是唯一可用的 catalog 合併依據
 * HTTP transport 的 target 與 subject 僅由這些型別化識別衍生
 */
public sealed interface ConceptIdentity permits
        ConceptIdentity.TypeConceptIdentity,
        ConceptIdentity.MethodConceptIdentity,
        ConceptIdentity.FieldConceptIdentity,
        ConceptIdentity.AnnotationUsageConceptIdentity,
        ConceptIdentity.TypeUsageConceptIdentity,
        ConceptIdentity.ApiRouteConceptIdentity,
        ConceptIdentity.MqDestinationConceptIdentity,
        ConceptIdentity.ScheduleConceptIdentity,
        ConceptIdentity.MapperStatementConceptIdentity,
        ConceptIdentity.MapperStatementVariantEvidenceIdentity {

    /** 概念的業務種類 */
    ConceptKind kind();

    /** 不解析 canonicalValue 的型別化穩定排序鍵 */
    String canonicalOrderKey();

    /** TYPE 識別保存 repository-relative 來源與 FQN，transport subject 由此衍生 */
    record TypeConceptIdentity(String sourceFile, String fullyQualifiedType) implements ConceptIdentity {

        public TypeConceptIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            fullyQualifiedType = ConceptIdentitySupport.requiredText(fullyQualifiedType, "fullyQualifiedType");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.TYPE;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + fullyQualifiedType;
        }
    }

    /** METHOD 識別保存完整 canonical MethodTarget，transport target 由此衍生 */
    record MethodConceptIdentity(MethodTarget target) implements ConceptIdentity {

        public MethodConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.METHOD;
        }

        @Override
        public String canonicalOrderKey() {
            return ConceptIdentitySupport.methodTargetKey(target);
        }
    }

    /** FIELD 識別保存來源、owner、名稱與宣告型別，transport subject 由此衍生 */
    record FieldConceptIdentity(String sourceFile, String ownerType, String fieldName, String declaredType)
            implements ConceptIdentity {

        public FieldConceptIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            ownerType = ConceptIdentitySupport.requiredText(ownerType, "ownerType");
            fieldName = ConceptIdentitySupport.requiredText(fieldName, "fieldName");
            declaredType = ConceptIdentitySupport.requiredText(declaredType, "declaredType");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.FIELD;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + ownerType + "|" + fieldName + "|" + declaredType;
        }
    }

    /** ANNOTATION_USAGE 識別保存精確宣告與 annotation，transport target 與 subject 由此衍生 */
    record AnnotationUsageConceptIdentity(
            String sourceFile,
            DeclarationSubjectIdentity annotatedDeclaration,
            String annotationIdentity) implements ConceptIdentity {

        public AnnotationUsageConceptIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            annotatedDeclaration = Objects.requireNonNull(
                    annotatedDeclaration, "annotatedDeclaration is required");
            annotationIdentity = ConceptIdentitySupport.requiredText(annotationIdentity, "annotationIdentity");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.ANNOTATION_USAGE;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + annotatedDeclaration.canonicalOrderKey() + "|" + annotationIdentity;
        }
    }

    /** TYPE_USAGE 識別保存 owner、slot 與解析型別，transport target 與 subject 由此衍生 */
    record TypeUsageConceptIdentity(
            String sourceFile,
            DeclarationSubjectIdentity ownerDeclaration,
            TypeUsageLocation usageLocation,
            String resolvedType) implements ConceptIdentity {

        public TypeUsageConceptIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            ownerDeclaration = Objects.requireNonNull(ownerDeclaration, "ownerDeclaration is required");
            usageLocation = Objects.requireNonNull(usageLocation, "usageLocation is required");
            resolvedType = ConceptIdentitySupport.requiredText(resolvedType, "resolvedType");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.TYPE_USAGE;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + ownerDeclaration.canonicalOrderKey() + "|" + usageLocation + "|"
                    + resolvedType;
        }
    }

    /** API_ROUTE 識別保存 target、HTTP verb 與 route，transport target 與 subject 由此衍生 */
    record ApiRouteConceptIdentity(MethodTarget target, String httpVerb, String route) implements ConceptIdentity {

        public ApiRouteConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            httpVerb = ConceptIdentitySupport.requiredText(httpVerb, "httpVerb");
            route = ConceptIdentitySupport.requiredText(route, "route");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.API_ROUTE;
        }

        @Override
        public String canonicalOrderKey() {
            return ConceptIdentitySupport.methodTargetKey(target) + "|" + httpVerb + "|" + route;
        }
    }

    /** MQ_DESTINATION 識別保存 target、broker 與 destination，transport target 與 subject 由此衍生 */
    record MqDestinationConceptIdentity(MethodTarget target, MqBroker broker, String destination)
            implements ConceptIdentity {

        public MqDestinationConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            broker = Objects.requireNonNull(broker, "broker is required");
            destination = ConceptIdentitySupport.requiredText(destination, "destination");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MQ_DESTINATION;
        }

        @Override
        public String canonicalOrderKey() {
            return ConceptIdentitySupport.methodTargetKey(target) + "|" + broker + "|" + destination;
        }
    }

    /** SCHEDULE 識別保存 target 與 trigger，transport target 與 subject 由此衍生 */
    record ScheduleConceptIdentity(
            MethodTarget target,
            ScheduleTriggerKind triggerKind,
            Optional<String> triggerValue) implements ConceptIdentity {

        public ScheduleConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
            triggerKind = Objects.requireNonNull(triggerKind, "triggerKind is required");
            triggerValue = Objects.requireNonNull(triggerValue, "triggerValue is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.SCHEDULE;
        }

        @Override
        public String canonicalOrderKey() {
            return ConceptIdentitySupport.methodTargetKey(target) + "|" + triggerKind + "|"
                    + triggerValue.orElse("<unresolved>");
        }
    }

    /** MAPPER_STATEMENT 候選只以 logical namespace 與 statement ID 識別 */
    record MapperStatementConceptIdentity(String namespace, String statementId)
            implements ConceptIdentity {

        public MapperStatementConceptIdentity {
            namespace = ConceptIdentitySupport.requiredText(namespace, "namespace");
            statementId = ConceptIdentitySupport.requiredText(statementId, "statementId");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MAPPER_STATEMENT;
        }

        @Override
        public String canonicalOrderKey() {
            return namespace + "|" + statementId;
        }
    }

    /** MAPPER_STATEMENT 候選所保留的完整 statement 變體證據識別 */
    record MapperStatementVariantEvidenceIdentity(MapperStatementIdentity mapperStatement)
            implements ConceptIdentity {

        public MapperStatementVariantEvidenceIdentity {
            mapperStatement = Objects.requireNonNull(mapperStatement, "mapperStatement is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MAPPER_STATEMENT;
        }

        @Override
        public String canonicalOrderKey() {
            return mapperStatement.namespace() + "|" + mapperStatement.statementId() + "|"
                    + mapperStatement.resourcePath() + "|"
                    + mapperStatement.databaseId().orElse("") + "|"
                    + mapperStatement.documentOrdinal() + "|"
                    + mapperStatement.representation();
        }
    }

    /** annotation 與 type usage 的宣告 subject，transport subject 僅由實際變體衍生 */
    sealed interface DeclarationSubjectIdentity permits
            ConceptIdentity.TypeDeclarationSubjectIdentity,
            ConceptIdentity.ResolvedMethodDeclarationSubjectIdentity,
            ConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity,
            ConceptIdentity.FieldDeclarationSubjectIdentity {

        /** 顯示用的精確宣告 subject */
        String displayValue();

        /** 不依賴顯示值的型別化排序鍵 */
        String canonicalOrderKey();
    }

    /** 型別宣告 subject，sourceFile 區分多模組中同 FQN 的宣告 */
    record TypeDeclarationSubjectIdentity(String sourceFile, String ownerType)
            implements DeclarationSubjectIdentity {

        public TypeDeclarationSubjectIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            ownerType = ConceptIdentitySupport.requiredText(ownerType, "ownerType");
        }

        @Override
        public String displayValue() {
            return ownerType;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + ownerType;
        }
    }

    /** 已解析方法宣告 subject，canonical MethodTarget 是 transport target 的唯一來源 */
    record ResolvedMethodDeclarationSubjectIdentity(MethodTarget target)
            implements DeclarationSubjectIdentity {

        public ResolvedMethodDeclarationSubjectIdentity {
            target = Objects.requireNonNull(target, "target is required");
        }

        @Override
        public String displayValue() {
            return ConceptIdentitySupport.methodDisplayValue(target);
        }

        @Override
        public String canonicalOrderKey() {
            return ConceptIdentitySupport.methodTargetKey(target);
        }
    }

    /** 未解析方法宣告 subject，保留 sourceFile 與完整簽名而不猜測 transport target */
    record UnresolvedMethodDeclarationSubjectIdentity(
            String sourceFile,
            String ownerType,
            String methodName,
            List<String> parameterTypes) implements DeclarationSubjectIdentity {

        public UnresolvedMethodDeclarationSubjectIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            ownerType = ConceptIdentitySupport.requiredText(ownerType, "ownerType");
            methodName = ConceptIdentitySupport.requiredText(methodName, "methodName");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes are required"));
        }

        @Override
        public String displayValue() {
            return ownerType + "#" + methodName + "(" + String.join(",", parameterTypes) + ")";
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + displayValue();
        }
    }

    /** 欄位宣告 subject，來源、owner、名稱與宣告型別供 transport 衍生精確 subject */
    record FieldDeclarationSubjectIdentity(
            String sourceFile,
            String ownerType,
            String fieldName,
            String declaredType) implements DeclarationSubjectIdentity {

        public FieldDeclarationSubjectIdentity {
            sourceFile = ConceptIdentitySupport.requiredText(sourceFile, "sourceFile");
            ownerType = ConceptIdentitySupport.requiredText(ownerType, "ownerType");
            fieldName = ConceptIdentitySupport.requiredText(fieldName, "fieldName");
            declaredType = ConceptIdentitySupport.requiredText(declaredType, "declaredType");
        }

        @Override
        public String displayValue() {
            return ownerType + "#" + fieldName + ":" + declaredType;
        }

        @Override
        public String canonicalOrderKey() {
            return sourceFile + "|" + displayValue();
        }
    }

    /** TYPE_USAGE 的宣告槽位，transport subject 由 slot 與 index 直接衍生 */
    record TypeUsageLocation(TypeUsageSlot slot, int index) {

        public TypeUsageLocation {
            slot = Objects.requireNonNull(slot, "slot is required");
            Assert.isTrue(index >= 0, "index must not be negative");
        }
    }

    /** binding 已證實型別出現的結構化宣告槽位種類 */
    enum TypeUsageSlot {
        IMPLEMENTED_TYPE,
        EXTENDED_TYPE,
        METHOD_PARAMETER,
        METHOD_RETURN,
        METHOD_BODY_OR_ANNOTATION_MEMBER,
        FIELD_DECLARATION
    }
}
