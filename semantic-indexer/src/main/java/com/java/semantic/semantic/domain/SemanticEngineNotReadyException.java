package com.java.semantic.semantic.domain;

/** JDT LS 尚未就緒 */
public final class SemanticEngineNotReadyException extends SemanticEngineException {

    public SemanticEngineNotReadyException() {
        super("SEMANTIC_ENGINE_NOT_READY", "semantic engine is not ready");
    }
}
