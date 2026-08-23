package com.java.semantic.syntax.domain;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;

import java.util.Objects;

/** 帶有精確來源檔案的 canonical declaration target */
public sealed interface ExactSourceDeclarationTarget permits
        ExactSourceDeclarationTarget.Type,
        ExactSourceDeclarationTarget.Method,
        ExactSourceDeclarationTarget.Member {

    String sourceFile();

    /** 精確來源型別 target */
    record Type(SourceTypeIdentity identity) implements ExactSourceDeclarationTarget {

        public Type {
            identity = Objects.requireNonNull(identity, "identity is required");
        }

        @Override
        public String sourceFile() {
            return identity.sourceFile();
        }
    }

    /** 精確 canonical 方法 target */
    record Method(MethodTarget identity) implements ExactSourceDeclarationTarget {

        public Method {
            identity = Objects.requireNonNull(identity, "identity is required");
        }

        @Override
        public String sourceFile() {
            return identity.sourceFile();
        }
    }

    /** 精確型別或方法範圍成員 target */
    record Member(SourceMemberIdentity identity) implements ExactSourceDeclarationTarget {

        public Member {
            identity = Objects.requireNonNull(identity, "identity is required");
        }

        @Override
        public String sourceFile() {
            return switch (identity) {
                case SourceMemberIdentity.TypeMember member -> member.ownerType().sourceFile();
                case SourceMemberIdentity.MethodScoped member -> member.declaringMethod().sourceFile();
            };
        }
    }
}
