package com.java.semantic.semantic.application;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import java.util.Objects;

/** repository-local reference 所屬的精確宣告 context */
public sealed interface InternalReferenceContext permits
        InternalReferenceContext.Type,
        InternalReferenceContext.Method {

    Kind kind();

    String sourceFile();

    /** context 類型的固定排序值 */
    enum Kind {
        TYPE,
        METHOD
    }

    /** 型別層級的 reference context */
    record Type(SourceTypeIdentity sourceType) implements InternalReferenceContext {

        public Type {
            sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        }

        @Override
        public Kind kind() {
            return Kind.TYPE;
        }

        @Override
        public String sourceFile() {
            return sourceType.sourceFile();
        }
    }

    /** 方法、建構式或其 lambda 內的 reference context */
    record Method(MethodTarget method) implements InternalReferenceContext {

        public Method {
            method = Objects.requireNonNull(method, "method is required");
        }

        @Override
        public Kind kind() {
            return Kind.METHOD;
        }

        @Override
        public String sourceFile() {
            return method.sourceFile();
        }
    }
}
