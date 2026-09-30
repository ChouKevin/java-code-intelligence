package com.java.semantic.indexer.store;

import com.java.semantic.model.git.GitEvidenceId;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitEvidenceState;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.bson.Document;

import java.util.Date;
import java.util.Objects;

/** Explicit current-schema BSON contract shared by job status and metadata publication. */
public final class GitMetadataResultCodec {
    private GitMetadataResultCodec() { }

    public static Document encode(GitHistoryManifest result) {
        GitHistoryManifest required = Objects.requireNonNull(result, "metadata result is required");
        if (required.state() != GitEvidenceState.READY || required.total() < 1L
                || required.ownership().scope() != GitPublicationScope.STANDALONE) {
            throw new IllegalArgumentException("metadata result must be READY and standalone");
        }
        return new Document("historyId", required.historyId().value())
                .append("catalogId", required.catalogId().value()).append("repoId", required.repositoryId().value())
                .append("branch", required.branch()).append("revision", required.revision().value())
                .append("preparedAt", Date.from(required.preparedAt())).append("state", required.state().name())
                .append("gitEvidenceVersion", required.gitEvidenceVersion()).append("total", required.total())
                .append("publicationScope", required.ownership().scope().name());
    }

    public static GitHistoryManifest decode(Document document) {
        Document required = Objects.requireNonNull(document, "metadata result document is required");
        if (!(required.get("gitEvidenceVersion") instanceof Integer version) || version != GitHistoryManifest.VERSION
                || !(required.get("total") instanceof Long total) || total < 1L
                || !(required.get("preparedAt") instanceof Date preparedAt)
                || !"READY".equals(required.get("state")) || !"STANDALONE".equals(required.get("publicationScope"))) {
            throw new IllegalArgumentException("invalid current metadata result document");
        }
        return new GitHistoryManifest(new GitEvidenceId(text(required, "historyId")),
                new GitEvidenceId(text(required, "catalogId")), RepositoryId.of(text(required, "repoId")),
                text(required, "branch"), new RepositoryRevision(text(required, "revision")), preparedAt.toInstant(),
                GitEvidenceState.READY, version, total, GitEvidenceOwnership.standalone());
    }

    private static String text(Document document, String field) {
        if (!(document.get(field) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("invalid metadata result field: " + field);
        }
        return value;
    }
}
