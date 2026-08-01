package com.java.semantic.syntax.application;

/** source symbol resolution 的完整狀態分類 */
public enum SourceSymbolResolutionStatus {
    RESOLVED,
    CONTEXT_NOT_FOUND,
    SYMBOL_NOT_FOUND,
    AMBIGUOUS_CONTEXT,
    AMBIGUOUS_SYMBOL,
    AMBIGUOUS_OCCURRENCE,
    UNRESOLVED_BINDING,
    POSITION_MISMATCH
}
