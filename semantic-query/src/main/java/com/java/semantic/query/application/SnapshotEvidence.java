package com.java.semantic.query.application;

import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.review.ReviewId;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;

/** Immutable header retained at admission; body readers do not reselect a generation or review. */
public record SnapshotEvidence(GitEvidenceOwnership ownership, String ownerJobId, long total,
        long textEntries, long textBytes, long fileTextBytesLimit, long snapshotTextBytesLimit) {
    static SnapshotEvidence decode(Document document) {
        try {
            GitPublicationScope scope = GitPublicationScope.valueOf(document.getString("scope"));
            String owner = document.getString("ownerJobId");
            if (Objects.isNull(owner) || owner.isBlank()) throw new IndexContractMismatchException();
            Optional<ReviewId> review = Optional.ofNullable(document.getString("reviewId")).map(ReviewId::new);
            GitEvidenceOwnership ownership = new GitEvidenceOwnership(scope, review);
            Document coverage = document.get("contentCoverage", Document.class);
            long total = count(document, "total");
            long entries = count(coverage, "textEntries");
            long bytes = count(coverage, "textBytes");
            long fileLimit = count(document, "fileTextBytesLimit");
            long snapshotLimit = count(document, "snapshotTextBytesLimit");
            if (count(coverage, "entryCount") != total || entries > total || bytes > snapshotLimit) {
                throw new IndexContractMismatchException();
            }
            return new SnapshotEvidence(ownership, owner, total, entries, bytes, fileLimit, snapshotLimit);
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static long count(Document document, String field) {
        if (Objects.isNull(document)) throw new IndexContractMismatchException();
        Object value = document.get(field);
        if (!(value instanceof Long || value instanceof Integer)) throw new IndexContractMismatchException();
        long count = ((Number) value).longValue();
        if (count < 0) throw new IndexContractMismatchException();
        return count;
    }
}
