package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.query.CurrentGeneration;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

public final class CurrentSymbolQueryService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;

    public CurrentSymbolQueryService(MongoTemplate template, CurrentGenerationSelector selector) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
    }

    public CurrentSymbol getSymbol(String repositoryId, String revision, String symbolId) {
        CurrentGeneration current = selector.select(repositoryId, revision, CurrentGenerationSelector.SYMBOLS);
        Assert.hasText(symbolId, "symbol id is required");
        try {
            Document symbol = Optional.ofNullable(template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()),
                            Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("symbolId", symbolId))).first())
                    .orElseThrow(IndexNotReadyException::new);
            String canonical = symbol.getString("canonical");
            String sourcePath = symbol.getString("sourcePath");
            if (!StringUtils.hasText(canonical) || !StringUtils.hasText(sourcePath)) {
                throw new IndexContractMismatchException();
            }
            return new CurrentSymbol(current, symbolId, canonical, sourcePath);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }
}
