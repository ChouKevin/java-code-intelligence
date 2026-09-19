package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.query.SelectedGeneration;
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
    private final SelectedGenerationGuard guard;
    private final Duration storageTimeout;

    public CurrentSymbolQueryService(MongoTemplate template, SelectedGenerationGuard guard, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public CurrentSymbol getSymbol(SelectedGeneration context, CodeFactIdentity identity) {
        SelectedGeneration selected = Objects.requireNonNull(context, "selected generation is required");
        guard.require(selected, SelectedGenerationGuard.SYMBOLS);
        CodeFactIdentity requestedIdentity = Objects.requireNonNull(identity, "code fact identity is required");
        if (!selected.repositoryId().equals(requestedIdentity.repositoryId())
                || !selected.revision().equals(requestedIdentity.repositoryRevision())) {
            throw new IllegalArgumentException("code fact identity repository and revision must match the selected generation");
        }
        guard.requireVisible(selected, requestedIdentity);
        CodeFactId symbolId = CodeFactId.from(requestedIdentity);
        try {
            Document symbol = template.getCollection(IndexCollections.SYMBOLS).find(Filters.and(
                            Filters.eq("repoId", selected.repositoryId().value()), Filters.eq("generationId", selected.generationId().value()),
                            Filters.eq("symbolId", symbolId.value()))).maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).first();
            if (Objects.isNull(symbol)) { throw new IndexNotReadyException(); }
            SymbolDocument decoded = decodeSymbol(symbol, selected);
            String canonical = text(symbol, "canonical");
            String sourcePath = text(symbol, "sourcePath");
            CodeFact fact = decoded.fact();
            if (!requestedIdentity.equals(fact.identity()) || !symbolId.equals(fact.id())
                    || !requestedIdentity.canonicalForm().equals(canonical) || !sourcePath.equals(decoded.range().sourceFile())) {
                throw new IndexContractMismatchException();
            }
            return new CurrentSymbol(selected, symbolId.value(), canonical, sourcePath);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexNotReadyException | IndexContractMismatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexContractMismatchException();
        }
    }

    private SymbolDocument decodeSymbol(Document stored, SelectedGeneration current) {
        try {
            Document converterDocument = new Document(stored);
            converterDocument.put("generationId", new Document("value", current.generationId().value()));
            SymbolDocument decoded = template.getConverter().read(SymbolDocument.class, converterDocument);
            CodeFact fact = decoded.fact();
            if (!current.repositoryId().equals(decoded.repositoryId()) || !current.generationId().equals(decoded.generationId())
                    || !current.repositoryId().equals(fact.identity().repositoryId()) || !current.revision().equals(fact.identity().repositoryRevision())
                    || !fact.id().equals(CodeFactId.from(fact.identity())) || !fact.id().value().equals(text(stored, "symbolId"))
                    || !fact.identity().canonicalForm().equals(text(stored, "canonical")) || !decoded.range().sourceFile().equals(text(stored, "sourcePath"))) {
                throw new IndexContractMismatchException();
            }
            return decoded;
        } catch (IndexContractMismatchException exception) {
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
