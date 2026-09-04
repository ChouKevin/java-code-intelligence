package com.java.semantic.query.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
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

    @Test
    void rejects_null_kind_elements_as_invalid_arguments() {
        Set<CodeFactKind> codeFactKinds = new LinkedHashSet<>();
        codeFactKinds.add(null);
        Set<EntryPointKind> entryPointKinds = new LinkedHashSet<>();
        entryPointKinds.add(null);

        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.SearchCodeRequest(
                REPOSITORY_ID, REVISION, "payment", codeFactKinds, Optional.empty(), 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.EntryPointRequest(
                REPOSITORY_ID, REVISION, entryPointKinds, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> new SemanticQueryContract.TypeMemberRequest(
                REPOSITORY_ID, REVISION, FACT_ID, codeFactKinds, 0, 20));
    }
}
