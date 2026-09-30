package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.query.application.ReadContextSelector.AdmittedComparison;
import com.java.semantic.query.application.ReadContextSelector.AdmittedContext;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.java.semantic.query.application.SemanticQueryContract.ContextRequest;
import com.java.semantic.query.application.SemanticQueryContract.ContextResult;
import com.java.semantic.query.application.SemanticQueryContract.ContextState;
import com.java.semantic.query.application.SemanticQueryContract.CurrentContextResult;
import com.java.semantic.query.application.SemanticQueryContract.EndpointKind;
import com.java.semantic.query.application.SemanticQueryContract.GuideInfo;
import com.java.semantic.query.application.SemanticQueryContract.JobIdentity;
import com.java.semantic.query.application.SemanticQueryContract.Page;
import com.java.semantic.query.application.SemanticQueryContract.ReadContext;
import com.java.semantic.query.application.SemanticQueryContract.RepositoryCollection;
import com.java.semantic.query.application.SemanticQueryContract.RepositoryItem;
import com.java.semantic.query.application.SemanticQueryContract.RepositoryRequest;
import com.java.semantic.query.application.SemanticQueryContract.ReviewContextResult;
import com.java.semantic.query.application.SemanticQueryContract.ReviewSideContext;
import com.java.semantic.query.application.SemanticQueryContract.SelectorKind;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Mongo registry and preparation discovery; never prepares work or substitutes a requested revision. */
public final class ContextDiscoveryService {
    private static final Pattern FAILURE_CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private final MongoTemplate template;
    private final ConfiguredReadPolicy policy;
    private final Duration timeout;
    private final CurrentGenerationSelector currents;
    private final ReadContextSelector contexts;
    private final ReviewManifestReadService reviews;
    private final ContextOverviewReader overviews;

    public ContextDiscoveryService(MongoTemplate template, ConfiguredReadPolicy policy, Duration timeout,
            CurrentGenerationSelector currents, ReadContextSelector contexts, ReviewManifestReadService reviews) {
        this.template = Objects.requireNonNull(template);
        this.policy = Objects.requireNonNull(policy);
        this.timeout = Objects.requireNonNull(timeout);
        this.currents = Objects.requireNonNull(currents);
        this.contexts = Objects.requireNonNull(contexts);
        this.reviews = Objects.requireNonNull(reviews);
        this.overviews = new ContextOverviewReader(template, timeout);
    }

    public RepositoryCollection listRepositories(RepositoryRequest request) {
        int limit = request.page().limit();
        String binding = QueryCursorCodec.binding("list_repositories", List.of(request.nameFilter().orElse(""), Integer.toString(limit)));
        Optional<String> after = request.page().cursor().map(cursor -> QueryCursorCodec.decode(cursor, binding, 1).getFirst())
                .map(value -> new RepositoryId(value).value());
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("configured", true));
        after.ifPresent(value -> filters.add(Filters.gt("repoId", value)));
        request.nameFilter().ifPresent(value -> {
            String literal = Pattern.quote(value);
            filters.add(Filters.or(Filters.regex("repoId", literal), Filters.regex("displayName", literal)));
        });
        List<RepositoryItem> items = new ArrayList<>(limit + 1);
        try (MongoCursor<Document> rows = template.getCollection(IndexCollections.REPOSITORIES).find(Filters.and(filters))
                .projection(Projections.include("repoId", "displayName", "defaultBranch", "configured", "currentPointer.revision"))
                .sort(Sorts.ascending("repoId")).batchSize(Math.max(32, limit + 1))
                .maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).iterator()) {
            while (items.size() <= limit && rows.hasNext()) {
                Document row = rows.next();
                RepositoryId repository = new RepositoryId(text(row, "repoId"));
                if (!policy.isRepositoryVisible(repository)) continue;
                Optional<String> revision = Optional.ofNullable(row.get("currentPointer", Document.class))
                        .map(pointer -> new RepositoryRevision(text(pointer, "revision")).value());
                items.add(new RepositoryItem(repository.value(), text(row, "displayName"), text(row, "defaultBranch"), true, revision));
            }
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
        boolean more = items.size() > limit;
        if (more) items.removeLast();
        Optional<String> next = more ? Optional.of(QueryCursorCodec.encode(binding, List.of(items.getLast().repositoryId()))) : Optional.empty();
        return new RepositoryCollection(items, new Page(items.size(), more, next));
    }

    public ContextResult getContext(ContextRequest request) {
        RepositoryId repository = new RepositoryId(request.repositoryId());
        if (!policy.isRepositoryVisible(repository)) throw new RepositoryNotFoundException();
        try {
            return request.selector().kind() == SelectorKind.CURRENT ? current(repository, request) : review(repository, request);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | SemanticIndexUnavailableException | IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private CurrentContextResult current(RepositoryId repository, ContextRequest request) {
        Document row = find(IndexCollections.REPOSITORIES, Filters.eq("repoId", repository.value()));
        if (Objects.isNull(row)) throw new RepositoryNotFoundException();
        Optional<String> branch = Boolean.TRUE.equals(row.get("configured")) ? Optional.of(text(row, "defaultBranch")) : Optional.empty();
        Optional<JobIdentity> active = Optional.ofNullable(find(IndexCollections.INDEX_JOBS,
                Filters.and(Filters.eq("repoId", repository.value()), Filters.eq("active", true)))).map(this::jobIdentity);
        if (!row.containsKey("currentPointer")) {
            return new CurrentContextResult(repository.value(), request.selector(), ContextState.UNINDEXED, branch, active,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        SourceContext source = currents.publishedContext(repository, row, ReadContextSelector.SOURCE_ONLY);
        Document pointer = row.get("currentPointer", Document.class);
        Instant indexedAt = date(pointer, "publishedAt");
        Optional<String> preparationBranch = Optional.ofNullable(find(IndexCollections.INDEX_JOBS,
                Filters.and(Filters.eq("repoId", repository.value()), Filters.eq("jobId", text(pointer, "committedJobId")))))
                .flatMap(job -> optionalText(job, "preparationBranch"));
        ReadContext context = ReadContext.current(repository.value(), source.selected().revision().value());
        return new CurrentContextResult(repository.value(), request.selector(), ContextState.READY, branch, active,
                Optional.of(context.revision()), Optional.of(indexedAt), preparationBranch, Optional.of(context),
                Optional.of(overviews.overview(source, policy.searchAccessPlan(repository), request.limit())), guide(source));
    }

    private ReviewContextResult review(RepositoryId repository, ContextRequest request) {
        Optional<ReviewSelection> selection = request.selector().requestedSelection();
        Document job;
        String reviewId;
        Document manifest;
        if (selection.isPresent()) {
            ReviewSelection wanted = selection.orElseThrow();
            List<Bson> filters = new ArrayList<>(List.of(Filters.eq("repoId", repository.value()), Filters.eq("operation", "REVIEW"),
                    Filters.eq("review.selectionKey", wanted.selectionKey()), Filters.eq("review.selection.kind", wanted.kind().name())));
            if (wanted.beforeRevision().isPresent()) {
                filters.add(Filters.eq("review.selection.beforeRevision", wanted.beforeRevision().orElseThrow().value()));
                filters.add(Filters.eq("review.selection.afterRevision", wanted.afterRevision().value()));
            } else {
                filters.add(Filters.eq("review.selection.revision", wanted.afterRevision().value()));
            }
            job = template.getCollection(IndexCollections.INDEX_JOBS).find(Filters.and(filters))
                    .sort(Sorts.orderBy(Sorts.descending("createdAt"), Sorts.descending("review.reviewId")))
                    .limit(1).maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(job)) return unavailable(request, ContextState.NOT_PREPARED, Optional.empty(), Optional.empty(), Optional.empty());
            reviewId = new ReviewId(text(job.get("review", Document.class), "reviewId")).value();
            manifest = find(IndexCollections.REVIEW_MANIFESTS, Filters.and(Filters.eq("repoId", repository.value()), Filters.eq("reviewId", reviewId)));
        } else {
            reviewId = request.selector().reviewId().orElseThrow();
            manifest = find(IndexCollections.REVIEW_MANIFESTS, Filters.and(Filters.eq("repoId", repository.value()), Filters.eq("reviewId", reviewId)));
            Bson identity = Objects.isNull(manifest) ? Filters.eq("review.reviewId", reviewId) : Filters.eq("jobId", text(manifest, "ownerJobId"));
            job = find(IndexCollections.INDEX_JOBS, Filters.and(Filters.eq("repoId", repository.value()), Filters.eq("operation", "REVIEW"), identity));
            if (Objects.isNull(job) && Objects.isNull(manifest)) {
                return unavailable(request, ContextState.NOT_PREPARED, Optional.empty(), Optional.empty(), Optional.empty());
            }
        }
        Optional<String> jobId = Objects.nonNull(job) ? Optional.of(jobIdentity(job).jobId())
                : Optional.of(text(manifest, "ownerJobId"));
        if (Objects.nonNull(job) && "FAILED".equals(text(job, "phase"))) {
            return unavailable(request, ContextState.FAILED, Optional.of(reviewId), jobId, failure(job));
        }
        if (Objects.isNull(manifest)) return unavailable(request, ContextState.PREPARING, Optional.of(reviewId), jobId, Optional.empty());
        String state = text(manifest, "state");
        if ("FAILED".equals(state)) return unavailable(request, ContextState.FAILED, Optional.of(reviewId), jobId, failure(manifest));
        if ("PREPARING".equals(state)) return unavailable(request, ContextState.PREPARING, Optional.of(reviewId), jobId, Optional.empty());
        if (!"READY".equals(state)) throw new IndexContractMismatchException();
        ReviewManifestDocument ready = reviews.decodeReady(manifest, repository, new ReviewId(reviewId));
        if (selection.filter(value -> !value.equals(ready.selection())).isPresent()
                || !ready.ownerJobId().equals(jobId.orElseThrow())) throw new IndexContractMismatchException();
        AdmittedComparison admitted = contexts.admitComparison(ready, ReadContextSelector.SOURCE_ONLY);
        SearchAccessPlan access = policy.searchAccessPlan(repository);
        ReviewSideContext before = admitted.before().map(side -> side(side, access, request.limit())).orElseGet(() ->
                new ReviewSideContext(EndpointKind.EMPTY_TREE, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        return new ReviewContextResult(repository.value(), request.selector(), ContextState.READY, Optional.of(reviewId), jobId,
                Optional.empty(), Optional.of(before), Optional.of(side(admitted.after(), access, request.limit())), Optional.of(admitted.comparisonContext()));
    }

    private ReviewSideContext side(AdmittedContext admitted, SearchAccessPlan access, int limit) {
        return new ReviewSideContext(EndpointKind.REVISION, Optional.of(admitted.context().revision()), Optional.of(admitted.context()),
                Optional.of(overviews.overview(admitted.source(), access, limit)), guide(admitted.source()));
    }
    private Optional<GuideInfo> guide(SourceContext source) {
        try {
            policy.requireGitEvidenceVisible(source.selected().repositoryId());
            return Optional.of(GuideInfo.from(source.guide()));
        } catch (RepositoryNotFoundException exception) {
            return Optional.empty();
        }
    }
    private static ReviewContextResult unavailable(ContextRequest request, ContextState state, Optional<String> reviewId,
            Optional<String> jobId, Optional<String> failure) {
        return new ReviewContextResult(request.repositoryId(), request.selector(), state, reviewId, jobId, failure,
                Optional.empty(), Optional.empty(), Optional.empty());
    }
    private JobIdentity jobIdentity(Document job) {
        if (!Integer.valueOf(IndexSchemaContract.PERSISTED_JOB_VERSION).equals(job.get("jobVersion"))) throw new IndexContractMismatchException();
        return new JobIdentity(text(job, "jobId"), text(job, "operation"), text(job, "phase"), optionalText(job, "requestId"));
    }
    private Document find(String collection, Bson filter) {
        return template.getCollection(collection).find(filter).maxTime(timeout.toMillis(), TimeUnit.MILLISECONDS).first();
    }
    private static Optional<String> failure(Document document) {
        return optionalText(document, "failureCategory").map(value -> {
            if (!FAILURE_CATEGORY.matcher(value).matches()) throw new IndexContractMismatchException();
            return value;
        });
    }
    private static Optional<String> optionalText(Document document, String field) {
        return document.containsKey(field) ? Optional.of(text(document, field)) : Optional.empty();
    }
    private static String text(Document document, String field) {
        if (Objects.isNull(document) || !(document.get(field) instanceof String value) || value.isBlank()) throw new IndexContractMismatchException();
        return value;
    }
    private static Instant date(Document document, String field) {
        if (!(document.get(field) instanceof Date value)) throw new IndexContractMismatchException();
        return value.toInstant();
    }
}
