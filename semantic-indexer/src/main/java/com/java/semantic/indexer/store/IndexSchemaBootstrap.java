package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Objects;

public final class IndexSchemaBootstrap {
    private final MongoIndexSchemaWriter writer;

    public IndexSchemaBootstrap(MongoTemplate template) { writer = new MongoIndexSchemaWriter(Objects.requireNonNull(template, "mongo template is required")); }

    public String bootstrap() {
        try {
            for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) { writer.createOrVerify(collection); }
            return IndexSchemaContract.fingerprint();
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
