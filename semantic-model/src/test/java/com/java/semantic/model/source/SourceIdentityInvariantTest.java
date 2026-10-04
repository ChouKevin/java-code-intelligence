package com.java.semantic.model.source;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceIdentityInvariantTest {

    @Test
    void rejectsRepositoryIdentitiesThatCouldEscapeOrAliasPublishedNamespaces() {
        for (String repositoryId : List.of(" ", "../private", "/absolute", "orders/subrepo", "Orders", "C:\\private")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceContext(repositoryId, "a".repeat(40)), repositoryId);
        }
    }

    @Test
    void rejectsNonExactOrNonCanonicalCommitIdentitiesInsteadOfSelectingCurrent() {
        for (String revision : List.of("main", "a".repeat(39), "a".repeat(41), "A".repeat(40), "g".repeat(40))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceContext("orders", revision), revision);
        }
    }
}
