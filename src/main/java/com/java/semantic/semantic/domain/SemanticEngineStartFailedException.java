package com.java.semantic.semantic.domain;

/** JDT LS 啟動失敗 */
public final class SemanticEngineStartFailedException extends SemanticEngineException {

    public SemanticEngineStartFailedException() {
        super("SEMANTIC_ENGINE_START_FAILED", "semantic engine failed to start");
    }
}
