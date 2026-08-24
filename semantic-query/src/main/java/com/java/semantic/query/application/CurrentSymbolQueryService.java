package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.query.CurrentGeneration;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class CurrentSymbolQueryService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;
    private final Duration storageTimeout;

    public CurrentSymbolQueryService(MongoTemplate template, CurrentGenerationSelector selector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public CurrentSymbol getSymbol(String repositoryId, String revision, CodeFactIdentity identity) {
        CodeFactIdentity requestedIdentity = Objects.requireNonNull(identity, "code fact identity is required");
        CurrentGeneration current = selector.selectCodeFact(repositoryId, revision, requestedIdentity);
        CodeFactId symbolId = CodeFactId.from(requestedIdentity);
        try {
            Document symbol = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()), Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("symbolId", symbolId.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(symbol)) { throw new IndexNotReadyException(); }
            String canonical = text(symbol, "canonical");
            String sourcePath = text(symbol, "sourcePath");
            if (!requestedIdentity.canonicalForm().equals(canonical)) { throw new IndexContractMismatchException(); }
            return new CurrentSymbol(current, symbolId.value(), canonical, sourcePath);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private static String text(Document document, String field) {
        Object value = document.get(field);
        if (!(value instanceof String text) || !StringUtils.hasText(text)) { throw new IndexContractMismatchException(); }
        return text;
    }
}
