package com.java.semantic.syntax.application;

import com.java.semantic.syntax.domain.SourceRange;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.identity.SourceTypeIdentity;

import java.util.Objects;
import java.util.Optional;

/** declaration identity 與 deterministic occurrence evidence 的封閉候選 */
public sealed interface SourceSymbolCandidate permits
        SourceSymbolCandidate.VariableLike,
        SourceSymbolCandidate.StaticConstant,
        SourceSymbolCandidate.Method,
        SourceSymbolCandidate.SourceType {

    SourceSymbolKind kind();

    String name();

    SourceRange declarationRange();

    SourceRange representativeOccurrence();

    /** method context 計算全部 matching occurrences，type context 固定計算單一 declaration identifier */
    int occurrenceCount();

    /** 欄位、record component、parameter、local 與 enum constant 候選 */
    record VariableLike(
            SourceSymbolKind kind,
            String name,
            SourceMemberIdentity identity,
            String writtenType,
            Optional<String> resolvedType,
            SourceRange declarationRange,
            SourceRange representativeOccurrence,
            int occurrenceCount) implements SourceSymbolCandidate {

        public VariableLike {
            kind = Objects.requireNonNull(kind, "kind is required");
            if (kind == SourceSymbolKind.STATIC_CONSTANT
                    || kind == SourceSymbolKind.METHOD
                    || kind == SourceSymbolKind.SOURCE_TYPE) {
                throw new IllegalArgumentException("kind requires a dedicated candidate variant");
            }
            name = requiredText(name, "name");
            identity = Objects.requireNonNull(identity, "identity is required");
            writtenType = requiredText(writtenType, "writtenType");
            resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            representativeOccurrence = Objects.requireNonNull(
                    representativeOccurrence, "representativeOccurrence is required");
            requirePositive(occurrenceCount);
        }
    }

    /** JDT 證明 initializer 為 compile-time constant expression 的 static final 欄位 */
    record StaticConstant(
            String name,
            SourceMemberIdentity identity,
            String writtenType,
            Optional<String> resolvedType,
            String initializerSource,
            SourceRange declarationRange,
            SourceRange representativeOccurrence,
            int occurrenceCount) implements SourceSymbolCandidate {

        public StaticConstant {
            name = requiredText(name, "name");
            identity = Objects.requireNonNull(identity, "identity is required");
            writtenType = requiredText(writtenType, "writtenType");
            resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
            initializerSource = requiredText(initializerSource, "initializerSource");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            representativeOccurrence = Objects.requireNonNull(
                    representativeOccurrence, "representativeOccurrence is required");
            requirePositive(occurrenceCount);
        }

        @Override
        public SourceSymbolKind kind() {
            return SourceSymbolKind.STATIC_CONSTANT;
        }
    }

    /** canonical MethodTarget candidate */
    record Method(
            MethodTarget identity,
            SourceRange declarationRange,
            SourceRange representativeOccurrence,
            int occurrenceCount) implements SourceSymbolCandidate {

        public Method {
            identity = Objects.requireNonNull(identity, "identity is required");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            representativeOccurrence = Objects.requireNonNull(
                    representativeOccurrence, "representativeOccurrence is required");
            requirePositive(occurrenceCount);
        }

        @Override
        public SourceSymbolKind kind() {
            return SourceSymbolKind.METHOD;
        }

        @Override
        public String name() {
            return identity.methodName();
        }
    }

    /** canonical source type candidate */
    record SourceType(
            SourceTypeIdentity identity,
            SourceRange declarationRange,
            SourceRange representativeOccurrence,
            int occurrenceCount) implements SourceSymbolCandidate {

        public SourceType {
            identity = Objects.requireNonNull(identity, "identity is required");
            declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
            representativeOccurrence = Objects.requireNonNull(
                    representativeOccurrence, "representativeOccurrence is required");
            requirePositive(occurrenceCount);
        }

        @Override
        public SourceSymbolKind kind() {
            return SourceSymbolKind.SOURCE_TYPE;
        }

        @Override
        public String name() {
            String fullyQualifiedName = identity.fullyQualifiedName();
            int separator = fullyQualifiedName.lastIndexOf('.');
            return separator < 0 ? fullyQualifiedName : fullyQualifiedName.substring(separator + 1);
        }
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return text;
    }

    private static void requirePositive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("occurrenceCount must be positive");
        }
    }
}
