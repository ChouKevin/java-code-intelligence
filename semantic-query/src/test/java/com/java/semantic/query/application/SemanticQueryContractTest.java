package com.java.semantic.query.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SemanticQueryContractTest {

    private static final String REPOSITORY_ID = "payment-service";
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String FACT_ID = "0".repeat(64);

    @Test
    void accepts_the_normalized_first_page() {
        assertDoesNotThrow(() -> new SemanticQueryContract.PageRequest(0, 20));
    }

    @Test
    void rejects_page_limits_outside_the_public_bounds() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.PageRequest(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.PageRequest(0, 101));
    }

    @Test
    void rejects_context_lines_outside_the_public_bounds() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.FactSourceRequest(
                REPOSITORY_ID, REVISION, FACT_ID, -1));
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.FactSourceRequest(
                REPOSITORY_ID, REVISION, FACT_ID, 21));
    }
}
