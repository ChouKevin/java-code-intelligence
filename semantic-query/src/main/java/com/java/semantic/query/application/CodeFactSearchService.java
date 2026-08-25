package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.CodeFactSearchResult;
import com.java.semantic.model.codefact.CodeFactSummary;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactTokenizer;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.query.config.SearchAccessPlan;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Derived search reader that exposes a row only after its authoritative declaration validates. */
public final class CodeFactSearchService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;
    private final Duration storageTimeout;
    private final CodeFactReadService codeFactReader;
    private final SourceIndexCoverageReader coverageReader;

    public CodeFactSearchService(MongoTemplate template, CurrentGenerationSelector selector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
        this.codeFactReader = new CodeFactReadService(template, selector, storageTimeout);
        this.coverageReader = new SourceIndexCoverageReader(template, storageTimeout);
    }

    public CodeFactSearchResult search(CodeFactSearchQuery query) {
        CodeFactSearchQuery requiredQuery = Objects.requireNonNull(query, "query is required");
        SearchAccessPlan accessPlan = selector.searchAccessPlan(requiredQuery.repositoryId().value());
        requiredQuery.packagePrefix().filter(prefix -> !accessPlan.isPackageVisible(prefix))
                .ifPresent(prefix -> { throw new RepositoryNotFoundException(); });
        CurrentGeneration current = selector.select(requiredQuery.repositoryId().value(), requiredQuery.revision().value(),
                CodeFactReadService.requirementsForSearchKinds(requiredQuery.kinds()));
        List<String> tokens = normalizedTokens(requiredQuery.query());
        try {
            Bson filter = accessPlan.authorized(filter(current, requiredQuery, tokens));
            long total = template.getCollection(IndexCollections.SEARCH).countDocuments(filter,
                    new com.mongodb.client.model.CountOptions().maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            FindIterable<Document> rows = template.getCollection(IndexCollections.SEARCH).find(filter)
                    .sort(Sorts.ascending("kind", "canonical", "factId")).skip(requiredQuery.offset()).limit(requiredQuery.limit())
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<CodeFactSummary> facts = new ArrayList<>();
            for (Document row : rows) {
                CodeFactSummary fact = authoritativeFact(row, current);
                selector.requireVisible(current, fact.fact().identity());
                facts.add(fact);
            }
            return new CodeFactSearchResult(current, requiredQuery, facts, total, requiredQuery.offset() + facts.size() < total,
                    coverageReader.coverage(current, accessPlan, requiredQuery.packagePrefix(), java.util.Optional.empty()));
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (RepositoryNotFoundException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private Bson filter(CurrentGeneration current, CodeFactSearchQuery query, List<String> tokens) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("repoId", current.repositoryId().value()));
        filters.add(Filters.eq("generationId", current.generationId().value()));
        if (!query.kinds().isEmpty()) {
            List<String> kinds = query.kinds().stream().map(CodeFactKind::name).sorted().toList();
            filters.add(Filters.in("kind", kinds));
        }
        query.packagePrefix().ifPresent(prefix -> filters.add(Filters.regex("package", "^" + Pattern.quote(prefix) + "(?:\\.|$)")));
        List<Pattern> patterns = tokens.stream().map(token -> Pattern.compile("^" + Pattern.quote(token))).toList();
        filters.add(new Document("tokens", new Document("$all", patterns)));
        return Filters.and(filters);
    }

    private CodeFactSummary authoritativeFact(Document row, CurrentGeneration current) {
        com.java.semantic.model.codefact.CodeFactDetails details = codeFactReader.authoritative(row, current);
        return new CodeFactSummary(details.fact(), details.location());
    }

    static List<String> normalizedTokens(String query) {
        List<String> tokens = CodeFactTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            throw new InvalidCodeFactQueryException("query has no searchable tokens", new IllegalArgumentException());
        }
        return tokens;
    }
}
