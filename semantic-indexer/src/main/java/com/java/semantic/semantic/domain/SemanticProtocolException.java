package com.java.semantic.semantic.domain;

/** JDT LS 語意協定請求失敗 */
public final class SemanticProtocolException extends SemanticEngineException {

    public SemanticProtocolException() {
        super("SEMANTIC_PROTOCOL_ERROR", "semantic protocol request failed");
    }
}
