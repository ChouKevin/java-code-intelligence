package com.java.semantic.api;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.application.ReviewQueryContract;
import com.java.semantic.query.application.ReviewQueryFacade;
import com.java.semantic.query.application.SemanticQueryContract;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** HTTP transport adapter for immutable review-scoped Semantic query operations. */
@RestController
@RequestMapping("/api/v1")
public final class ReviewQueryController {
    private final ReviewQueryFacade facade;

    public ReviewQueryController(ReviewQueryFacade facade) {
        this.facade = Objects.requireNonNull(facade, "review query facade is required");
    }

    @GetMapping("/repositories/{repositoryId}/reviews/{reviewId}")
    public ReviewQueryContract.ReviewDetails getReview(@PathVariable String repositoryId, @PathVariable String reviewId) {
        return facade.getReview(new ReviewQueryContract.ReviewRequest(requiredText(repositoryId, "repositoryId"),
                requiredText(reviewId, "reviewId")));
    }

    @PostMapping("/reviews/search-code")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> searchCode(
            @RequestBody ReviewSearchCodeHttpRequest request) {
        return facade.searchCode(new ReviewQueryContract.ReviewSearchCodeRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.query(), "query"), Objects.requireNonNullElse(request.kinds(), Set.of()), optionalText(request.packagePrefix()),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/reviews/fact-source")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.FactSourceResult> getFactSource(
            @RequestBody ReviewFactSourceHttpRequest request) {
        return facade.getFactSource(new ReviewQueryContract.ReviewFactSourceRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.factId(), "factId"), contextLines(request.contextLines())));
    }

    @PostMapping("/reviews/entry-points")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> listEntryPoints(
            @RequestBody ReviewEntryPointHttpRequest request) {
        return facade.listEntryPoints(new ReviewQueryContract.ReviewEntryPointRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                Objects.requireNonNullElse(request.kinds(), Set.of()), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/reviews/api-routes")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findApiRoutes(
            @RequestBody ReviewApiRouteHttpRequest request) {
        return facade.findApiRoutes(new ReviewQueryContract.ReviewApiRouteRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                required(request.httpMethod(), "httpMethod"), requiredText(request.path(), "path"), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/reviews/event-listeners")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findEventListeners(
            @RequestBody ReviewEventListenerHttpRequest request) {
        return facade.findEventListeners(new ReviewQueryContract.ReviewEventListenerRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.eventType(), "eventType"), offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/reviews/type-members")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> listTypeMembers(
            @RequestBody ReviewTypeMemberHttpRequest request) {
        return facade.listTypeMembers(new ReviewQueryContract.ReviewTypeMemberRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.typeFactId(), "typeFactId"), Objects.requireNonNullElse(request.kinds(), Set.of()),
                offset(request.offset()), limit(request.limit())));
    }

    @PostMapping("/reviews/method-implementations")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findMethodImplementations(
            @RequestBody ReviewMethodRelationHttpRequest request) {
        return facade.findMethodImplementations(methodRelationRequest(request));
    }

    @PostMapping("/reviews/references")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findReferences(
            @RequestBody ReviewRelationHttpRequest request) {
        return facade.findReferences(relationRequest(request));
    }

    @PostMapping("/reviews/callers")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findCallers(
            @RequestBody ReviewMethodRelationHttpRequest request) {
        return facade.findCallers(methodRelationRequest(request));
    }

    @PostMapping("/reviews/callees")
    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findCallees(
            @RequestBody ReviewMethodRelationHttpRequest request) {
        return facade.findCallees(methodRelationRequest(request));
    }

    private static ReviewQueryContract.ReviewRelationRequest methodRelationRequest(ReviewMethodRelationHttpRequest request) {
        return new ReviewQueryContract.ReviewRelationRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.methodFactId(), "methodFactId"), offset(request.offset()), limit(request.limit()));
    }

    private static ReviewQueryContract.ReviewRelationRequest relationRequest(ReviewRelationHttpRequest request) {
        return new ReviewQueryContract.ReviewRelationRequest(requiredText(request.repositoryId(), "repositoryId"),
                requiredText(request.reviewId(), "reviewId"), required(request.side(), "side"), requiredText(request.revision(), "revision"),
                requiredText(request.factId(), "factId"), offset(request.offset()), limit(request.limit()));
    }

    private static String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static <T> T required(T value, String field) {
        return Optional.ofNullable(value).orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private static Optional<String> optionalText(String value) {
        return Optional.ofNullable(value);
    }

    private static int offset(Integer offset) {
        return Objects.requireNonNullElse(offset, 0);
    }

    private static int limit(Integer limit) {
        return Objects.requireNonNullElse(limit, SemanticQueryContract.DEFAULT_LIMIT);
    }

    private static int contextLines(Integer contextLines) {
        return Objects.requireNonNullElse(contextLines, 0);
    }

    record ReviewSearchCodeHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision, String query,
                                       Set<CodeFactKind> kinds, String packagePrefix, Integer offset, Integer limit) { }
    record ReviewFactSourceHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision, String factId,
                                       Integer contextLines) { }
    record ReviewEntryPointHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                       Set<EntryPointKind> kinds, Integer offset, Integer limit) { }
    record ReviewApiRouteHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                     SemanticQueryContract.HttpMethod httpMethod, String path, Integer offset, Integer limit) { }
    record ReviewEventListenerHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                          String eventType, Integer offset, Integer limit) { }
    record ReviewTypeMemberHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision, String typeFactId,
                                       Set<CodeFactKind> kinds, Integer offset, Integer limit) { }
    record ReviewMethodRelationHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                           String methodFactId, Integer offset, Integer limit) { }
    record ReviewRelationHttpRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                     String factId, Integer offset, Integer limit) { }
}
