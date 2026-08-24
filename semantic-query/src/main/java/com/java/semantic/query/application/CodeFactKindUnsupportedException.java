package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactKind;

public final class CodeFactKindUnsupportedException extends RuntimeException {
    public CodeFactKindUnsupportedException(CodeFactKind kind) { super("CODE_FACT_KIND_UNSUPPORTED: " + kind.name()); }
}
