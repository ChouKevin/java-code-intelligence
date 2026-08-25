package com.java.semantic.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CodeFactControllerContractTest {

    @Test
    void exposes_a_flat_code_fact_http_surface() {
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.CodeFactController"));
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.dto.SearchCodeFactsRequest"));
        assertDoesNotThrow(() -> Class.forName("com.java.semantic.api.dto.GetCodeFactRequest"));
    }
}
