package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Objects;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 使用類概念的唯一識別，供 ANNOTATION_USAGE 與 TYPE_USAGE 以穩定值合併目錄 */
public sealed interface UsageConceptIdentity extends ConceptIdentity permits
        UsageConceptIdentity.AnnotationUsageConceptIdentity,
        UsageConceptIdentity.TypeUsageConceptIdentity {

    /** ANNOTATION_USAGE 識別保存精確宣告與已解析或原始 annotation */
    record AnnotationUsageConceptIdentity(
            DeclarationSubjectIdentity annotatedDeclaration,
            AnnotationIdentity annotationIdentity) implements UsageConceptIdentity {

        public AnnotationUsageConceptIdentity {
            annotatedDeclaration = Objects.requireNonNull(
                    annotatedDeclaration, "annotatedDeclaration is required");
            annotationIdentity = Objects.requireNonNull(annotationIdentity, "annotationIdentity is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.ANNOTATION_USAGE;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.ANNOTATION_USAGE;
        }
    }

    /** TYPE_USAGE 識別保存 owner、location、巢狀 path 與已解析引用型別 */
    record TypeUsageConceptIdentity(
            DeclarationSubjectIdentity owner,
            TypeUsageLocation location,
            List<TypeUsagePath> path,
            ReferencedTypeIdentity referencedType) implements UsageConceptIdentity {

        public TypeUsageConceptIdentity {
            owner = Objects.requireNonNull(owner, "owner is required");
            location = Objects.requireNonNull(location, "location is required");
            path = TypeUsagePath.copyOf(path);
            referencedType = Objects.requireNonNull(referencedType, "referencedType is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.TYPE_USAGE;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.TYPE_USAGE;
        }
    }

    /** annotation 與 type usage 的宣告 subject，transport subject 僅由實際變體衍生 */
    sealed interface DeclarationSubjectIdentity permits
            UsageConceptIdentity.TypeDeclarationSubjectIdentity,
            UsageConceptIdentity.ResolvedMethodDeclarationSubjectIdentity,
            UsageConceptIdentity.UnresolvedMethodDeclarationSubjectIdentity,
            UsageConceptIdentity.FieldDeclarationSubjectIdentity {

        /** repository-relative 來源檔 */
        String sourceFile();

        /** 顯示用的精確宣告 subject */
        String displayValue();

    }

    /** 型別宣告 subject 組合已驗證來源型別，sourceFile 區分多模組同 FQN 宣告 */
    record TypeDeclarationSubjectIdentity(SourceTypeIdentity type)
            implements DeclarationSubjectIdentity {

        public TypeDeclarationSubjectIdentity {
            type = Objects.requireNonNull(type, "type is required");
        }

        @Override
        public String sourceFile() {
            return type.sourceFile();
        }

        @Override
        public String displayValue() {
            return type.fullyQualifiedName();
        }

    }

    /** 已解析方法宣告 subject，canonical MethodTarget 是 transport target 的唯一來源 */
    record ResolvedMethodDeclarationSubjectIdentity(MethodTarget target)
            implements DeclarationSubjectIdentity {

        public ResolvedMethodDeclarationSubjectIdentity {
            target = Objects.requireNonNull(target, "target is required");
        }

        @Override
        public String sourceFile() {
            return target.sourceFile();
        }

        @Override
        public String displayValue() {
            return methodDisplayValue(target);
        }

    }

    /** 未解析方法宣告 subject 組合 owner 與原始簽名而不猜測 transport target */
    record UnresolvedMethodDeclarationSubjectIdentity(
            SourceTypeIdentity owner,
            MethodDeclarationSignature signature) implements DeclarationSubjectIdentity {

        public UnresolvedMethodDeclarationSubjectIdentity {
            owner = Objects.requireNonNull(owner, "owner is required");
            signature = Objects.requireNonNull(signature, "signature is required");
        }

        @Override
        public String sourceFile() {
            return owner.sourceFile();
        }

        @Override
        public String displayValue() {
            return owner.fullyQualifiedName() + "#" + signature.displayValue();
        }

    }

    /** 欄位宣告 subject 組合來源成員，宣告型別屬於 catalog details 而不是 identity */
    record FieldDeclarationSubjectIdentity(TypeMember field) implements DeclarationSubjectIdentity {

        public FieldDeclarationSubjectIdentity {
            field = Objects.requireNonNull(field, "field is required");
        }

        @Override
        public String sourceFile() {
            return field.ownerType().sourceFile();
        }

        @Override
        public String displayValue() {
            return field.ownerType().fullyQualifiedName() + "#" + field.name();
        }

    }

    /** annotation 的已解析或原始唯一識別 */
    sealed interface AnnotationIdentity permits
            UsageConceptIdentity.ResolvedAnnotationIdentity,
            UsageConceptIdentity.UnresolvedAnnotationIdentity {

        /** 提供 transport 顯示的 annotation 名稱 */
        String displayValue();

    }

    /** binding 已解析的 annotation 型別 */
    record ResolvedAnnotationIdentity(JavaTypeIdentity javaType) implements AnnotationIdentity {

        public ResolvedAnnotationIdentity {
            javaType = Objects.requireNonNull(javaType, "javaType is required");
        }

        @Override
        public String displayValue() {
            return javaType.fullyQualifiedName();
        }

    }

    /** binding 未解析時保留原始 annotation 寫法 */
    record UnresolvedAnnotationIdentity(String writtenName) implements AnnotationIdentity {

        public UnresolvedAnnotationIdentity {
            writtenName = requiredText(writtenName, "writtenName");
        }

        @Override
        public String displayValue() {
            return writtenName;
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

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        Assert.isTrue(StringUtils.hasText(text), fieldName + " is required");
        return text;
    }

    private static String methodDisplayValue(MethodTarget target) {
        MethodTarget methodTarget = Objects.requireNonNull(target, "target is required");
        String qualifiedType = methodTarget.packageName().isEmpty()
                ? methodTarget.className()
                : methodTarget.packageName() + "." + methodTarget.className();
        return qualifiedType + "#" + methodTarget.methodName()
                + "(" + String.join(",", methodTarget.parameterTypes()) + ")";
    }
}
