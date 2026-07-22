package com.java.semantic.semantic.domain;

import com.java.semantic.diagnostic.ExpectedFailure;

/** JDT LS 語意請求逾時 */
public final class SemanticRequestTimeoutException extends SemanticEngineException implements ExpectedFailure {

    public SemanticRequestTimeoutException() {
        super("SEMANTIC_REQUEST_TIMEOUT", "semantic request timed out");
    }
}
