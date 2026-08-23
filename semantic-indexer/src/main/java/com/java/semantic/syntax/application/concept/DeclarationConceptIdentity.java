package com.java.semantic.syntax.application.concept;

import java.util.Objects;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceMemberIdentity.TypeMember;

/** 宣告類概念的唯一識別，供 TYPE、METHOD 與 FIELD 以穩定值合併目錄 */
public sealed interface DeclarationConceptIdentity extends ConceptIdentity permits
        DeclarationConceptIdentity.TypeConceptIdentity,
        DeclarationConceptIdentity.MethodConceptIdentity,
        DeclarationConceptIdentity.FieldConceptIdentity {

    /** TYPE 識別組合已驗證的來源型別，transport 只投影其座標 */
    record TypeConceptIdentity(SourceTypeIdentity type) implements DeclarationConceptIdentity {

        public TypeConceptIdentity {
            type = Objects.requireNonNull(type, "type is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.TYPE;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.TYPE;
        }
    }

    /** METHOD 識別保存完整 canonical MethodTarget，transport target 由此衍生 */
    record MethodConceptIdentity(MethodTarget target) implements DeclarationConceptIdentity {

        public MethodConceptIdentity {
            target = Objects.requireNonNull(target, "target is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.METHOD;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.METHOD;
        }
    }

    /** FIELD 識別組合來源型別成員，宣告型別僅保留在 field details */
    record FieldConceptIdentity(TypeMember field)
            implements DeclarationConceptIdentity {

        public FieldConceptIdentity {
            field = Objects.requireNonNull(field, "field is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.FIELD;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.FIELD;
        }
    }

}
