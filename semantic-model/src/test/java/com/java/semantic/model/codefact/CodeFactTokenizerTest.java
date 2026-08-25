package com.java.semantic.model.codefact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeFactTokenizerTest {
    @Test
    void normalizes_camel_digit_and_separator_boundaries_with_deterministic_deduplication() {
        assertEquals(List.of("get2", "d", "find", "payment"),
                CodeFactTokenizer.tokenize("get2D/find_payment.find_payment"));
    }
}
