package com.java.semantic.model.source;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceIdentityInvariantTest {

    @Test
    void rejects_old_policy_instead_of_admitting_unprotected_source() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRevisionManifest(1, 1,
                new SourceContext("orders", "a".repeat(40)), java.time.Instant.EPOCH,
                new SourceReadContract.GuideInfo(SourceReadContract.GuideState.DISABLED, java.util.Optional.empty(),
                        java.util.Optional.empty(), SourceReadContract.GuideFreshness.NOT_VERIFIED),
                new SourceRevisionManifest.Coverage(0, 0, java.util.Map.of()), "b".repeat(64)));
    }

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
