package com.java.semantic.indexer.store;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitMetadataResultCodecTest {
    private static final String HISTORY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CATALOG_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Test
    void persists_flat_identities_and_bson_date_precision_for_a_ready_history() {
        GitHistoryManifest result = result();
        Document document = GitMetadataResultCodec.encode(result);
        assertThat(document).containsEntry("historyId", HISTORY_ID).containsEntry("catalogId", CATALOG_ID)
                .containsEntry("repoId", "orders").containsEntry("branch", "release/custom")
                .containsEntry("revision", "a".repeat(40)).containsEntry("state", "READY")
                .containsEntry("publicationScope", "STANDALONE").containsEntry("total", 3L)
                .containsEntry("preparedAt", Date.from(result.preparedAt()));
        GitHistoryManifest decoded = GitMetadataResultCodec.decode(document);
        assertThat(decoded).isEqualTo(new GitHistoryManifest(result.historyId(), result.catalogId(), result.repositoryId(),
                result.branch(), result.revision(), Instant.ofEpochMilli(result.preparedAt().toEpochMilli()),
                result.state(), result.gitEvidenceVersion(), result.total(), result.ownership()));
    }

    @Test
    void refuses_old_versions_non_ready_results_and_wrong_bson_types_instead_of_legacy_or_optional_decoding() {
        Document current = GitMetadataResultCodec.encode(result());
        for (Document invalid : new Document[] {
                new Document(current).append("gitEvidenceVersion", GitHistoryManifest.VERSION - 1),
                new Document(current).append("gitEvidenceVersion", (long) GitHistoryManifest.VERSION),
                new Document(current).append("total", 3),
                new Document(current).append("total", 0L),
                new Document(current).append("preparedAt", "2026-09-28T12:30:00Z"),
                new Document(current).append("state", "PREPARING"),
                new Document(current).append("publicationScope", "REVIEW"),
                new Document(current).append("historyId", new Document("value", HISTORY_ID)) }) {
            assertThatThrownBy(() -> GitMetadataResultCodec.decode(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static GitHistoryManifest result() {
        return new GitHistoryManifest(new GitEvidenceId(HISTORY_ID), new GitEvidenceId(CATALOG_ID), RepositoryId.of("orders"),
                "release/custom", new RepositoryRevision("a".repeat(40)), Instant.parse("2026-09-28T12:30:00.123456789Z"),
                GitEvidenceState.READY, GitHistoryManifest.VERSION, 3L, GitEvidenceOwnership.standalone());
    }
}
