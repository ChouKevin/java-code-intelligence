package com.java.semantic.model.codefact;

import java.util.Objects;

/** Deterministic display fields shared by persisted search writers and readers. */
public final class CodeFactDisplay {
    private CodeFactDisplay() {
        throw new UnsupportedOperationException("utility class");
    }

    public static String displayName(CanonicalIdentity identity) {
        if (identity instanceof EntryPointIdentity entry) {
            return entry.method().methodName();
        }
        if (identity instanceof MethodTarget method) {
            return method.methodName();
        }
        if (identity instanceof SourceTypeIdentity type) {
            return type.javaType().className();
        }
        if (identity instanceof MemberIdentity member) {
            return member.name();
        }
        if (identity instanceof MapperStatementIdentity mapper) {
            return mapper.statementId();
        }
        if (identity instanceof RelationIdentity relation) {
            if (relation.target() instanceof RelationTarget.Internal internal) {
                return displayName(internal.identity().canonicalIdentity());
            }
            if (relation.target() instanceof RelationTarget.External external) {
                return external.target().canonicalForm();
            }
        }
        throw new IllegalArgumentException("unsupported indexed search identity");
    }

    public static String signature(CanonicalIdentity identity) {
        MethodTarget method = identity instanceof EntryPointIdentity entry ? entry.method()
                : identity instanceof MethodTarget target ? target : null;
        return Objects.isNull(method) ? "" : method.methodName() + "(" + String.join(", ", method.parameterTypes()) + ")";
    }
}
