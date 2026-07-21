package com.java.semantic.semantic.domain;

/** JDT LS 語意請求逾時 */
public final class SemanticRequestTimeoutException extends SemanticEngineException {

    public SemanticRequestTimeoutException() {
        super("SEMANTIC_REQUEST_TIMEOUT", "semantic request timed out");
    }
}
