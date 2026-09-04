package com.java.semantic.query.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;

class SourceSnippetMapperTest {

    @Test
    void converts_exclusive_internal_end_at_next_line_start_to_inclusive_previous_line() {
        SourceRange range = new SourceRange("src/A.java",
                new SyntaxRange(new SyntaxPosition(9, 4), new SyntaxPosition(10, 0)));

        String source = "0\n1\n2\n3\n4\n5\n6\n7\n8\nline nine\nline ten\n";
        SemanticQueryContract.SourceSnippet result = SourceSnippetMapper.toSnippet(range, source);

        assertEquals(10, result.startLine());
        assertEquals(10, result.endLine());
        assertEquals(" nine\n", result.code());
    }

    @Test
    void rejects_zero_length_public_fact_range() {
        SourceRange range = new SourceRange("src/A.java",
                new SyntaxRange(new SyntaxPosition(1, 3), new SyntaxPosition(1, 3)));

        assertThrows(IndexContractMismatchException.class, () -> SourceSnippetMapper.toFactRange(range));
    }
}
