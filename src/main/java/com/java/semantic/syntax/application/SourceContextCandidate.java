package com.java.semantic.syntax.application;

/** resolver 提供的 competing source context evidence 封閉集合 */
public sealed interface SourceContextCandidate permits
        SourceTypeContextCandidate,
        SourceMethodContextCandidate {

    SourceSymbolKind kind();
}
