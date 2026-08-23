package com.java.semantic.syntax.domain;

import java.util.List;

/** 編譯單元層級的來源證據 */
public record CompilationUnitContext(List<String> imports) {

    public CompilationUnitContext {
        imports = List.copyOf(imports);
    }
}
