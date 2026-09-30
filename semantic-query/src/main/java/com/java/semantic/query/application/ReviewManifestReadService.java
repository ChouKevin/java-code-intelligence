package com.java.semantic.query.application;

import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewState;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

/** Reads only complete immutable review manifests after applying repository visibility. */
public final class ReviewManifestReadService {
    private final MongoTemplate template;
    private final ConfiguredReadPolicy readPolicy;
    private final Duration storageTimeout;

    public ReviewManifestReadService(MongoTemplate template, ConfiguredReadPolicy readPolicy, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "read policy is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public ReviewManifestDocument requireReady(RepositoryId repositoryId, ReviewId reviewId) {
        RepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        ReviewId requiredReviewId = Objects.requireNonNull(reviewId, "review id is required");
        if (!readPolicy.isRepositoryVisible(requiredRepositoryId)) {
            throw new RepositoryNotFoundException();
        }
        try {
            Document document = template.getCollection(IndexCollections.REVIEW_MANIFESTS).find(Filters.and(
                    Filters.eq("repoId", requiredRepositoryId.value()), Filters.eq("reviewId", requiredReviewId.value())))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(document)) {
                throw stateFromReviewJob(requiredRepositoryId, requiredReviewId);
            }
            String state = requiredText(document, "state");
            if (ReviewState.PREPARING.name().equals(state)) {
                throw new ReviewNotReadyException();
            }
            if (ReviewState.FAILED.name().equals(state)) {
                throw new ReviewFailedException();
            }
            if (!ReviewState.READY.name().equals(state)) {
                throw new IndexContractMismatchException();
            }
            return decodeReady(document, requiredRepositoryId, requiredReviewId);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (ReviewNotFoundException | ReviewNotReadyException | ReviewFailedException | RepositoryNotFoundException
                | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private RuntimeException stateFromReviewJob(RepositoryId repositoryId, ReviewId reviewId) {
        Document job = template.getCollection(IndexCollections.INDEX_JOBS).find(Filters.and(
                Filters.eq("repoId", repositoryId.value()), Filters.eq("operation", "REVIEW"),
                Filters.eq("review.reviewId", reviewId.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
        if (Objects.isNull(job)) {
            return new ReviewNotFoundException();
        }
        if ("FAILED".equals(requiredText(job, "phase"))) {
            return new ReviewFailedException();
        }
        return new ReviewNotReadyException();
    }

    ReviewManifestDocument decodeReady(Document document, RepositoryId repositoryId, ReviewId reviewId) {
        try {
            if (!repositoryId.value().equals(requiredText(document, "repoId")) || !reviewId.value().equals(requiredText(document, "reviewId"))
                    || requiredInteger(document, "reviewContractVersion") != IndexSchemaContract.REVIEW_MANIFEST_VERSION) {
                throw new IndexContractMismatchException();
            }
            Document selectionDocument = requiredDocument(document, "selection");
            ReviewComparisonType kind = ReviewComparisonType.valueOf(requiredText(selectionDocument, "kind"));
            ReviewSelection selection = new ReviewSelection(kind,
                    Optional.ofNullable(selectionDocument.getString("beforeRevision")).map(RepositoryRevision::ofSha),
                    RepositoryRevision.ofSha(requiredText(selectionDocument, kind == ReviewComparisonType.COMMIT ? "revision" : "afterRevision")));
            Document resolvedDocument = requiredDocument(document, "resolvedEndpoints");
            ResolvedReviewEndpoints resolved = new ResolvedReviewEndpoints(
                    Optional.ofNullable(resolvedDocument.getString("beforeRevision")).map(RepositoryRevision::ofSha),
                    RepositoryRevision.ofSha(requiredText(resolvedDocument, "afterRevision")),
                    ReviewBaselineRule.valueOf(requiredText(resolvedDocument, "baselineRule")));
            Optional<ReviewEndpoint> before = Optional.ofNullable(document.get("before", Document.class)).map(this::endpoint);
            ReviewEndpoint after = endpoint(requiredDocument(document, "after"));
            return new ReviewManifestDocument(repositoryId, reviewId, requiredText(document, "ownerJobId"), requiredInteger(document, "reviewContractVersion"),
                    ReviewState.READY, selection, Optional.of(resolved), before, Optional.of(after),
                    Optional.of(new GitComparisonId(requiredText(document, "comparisonId"))), requiredDate(document, "createdAt").toInstant(),
                    Optional.of(requiredDate(document, "publishedAt").toInstant()), Optional.empty());
        } catch (IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private ReviewEndpoint endpoint(Document document) {
        Document generationDocument = requiredDocument(document, "generation");
        SealedGeneration generation = template.getConverter().read(SealedGeneration.class, generationDocument);
        return new ReviewEndpoint(generation, new GitSnapshotId(requiredText(document, "snapshotId")));
    }

    private static String requiredText(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IndexContractMismatchException();
        }
        return text;
    }

    private static int requiredInteger(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Integer number)) {
            throw new IndexContractMismatchException();
        }
        return number;
    }

    private static Document requiredDocument(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Document nested)) {
            throw new IndexContractMismatchException();
        }
        return nested;
    }

    private static Date requiredDate(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof Date date)) {
            throw new IndexContractMismatchException();
        }
        return date;
    }
}
