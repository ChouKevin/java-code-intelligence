package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bson.Document;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Read-only worker gate that prevents projection generation against an unbootstrapped schema. */
final class MongoIndexSchemaReadinessVerifier {
    private final MongoTemplate template;

    MongoIndexSchemaReadinessVerifier(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    void verify() {
        try {
            for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) {
                if (!template.collectionExists(collection.name())) {
                    throw new IndexSchemaMaintenanceRequiredException("missing collection " + collection.name());
                }
                MongoCollection<Document> mongoCollection = template.getCollection(collection.name());
                Map<String, Document> installed = new LinkedHashMap<>();
                for (Document index : mongoCollection.listIndexes()) {
                    installed.put(index.getString("name"), index);
                }
                for (IndexSchemaContract.IndexSpec required : collection.indexes()) {
                    Document actual = installed.get(required.name());
                    if (Objects.isNull(actual)) {
                        throw new IndexSchemaMaintenanceRequiredException("missing index " + collection.name() + "." + required.name());
                    }
                    if (!MongoIndexDefinitionMatcher.matches(actual, required)) {
                        throw new IndexSchemaMaintenanceRequiredException("conflicting index " + collection.name() + "." + required.name());
                    }
                }
            }
        } catch (DataAccessResourceFailureException | MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
