package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class MongoIndexSchemaWriter {
    private final MongoTemplate template;
    private final CollectionCreationGate collectionCreationGate;

    MongoIndexSchemaWriter(MongoTemplate template) { this(template, () -> { }); }

    MongoIndexSchemaWriter(MongoTemplate template, CollectionCreationGate collectionCreationGate) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.collectionCreationGate = Objects.requireNonNull(collectionCreationGate, "collection creation gate is required");
    }

    void createOrVerify(IndexSchemaContract.CollectionSpec collection) {
        if (!template.collectionExists(collection.name())) {
            collectionCreationGate.afterCollectionExistsCheck();
            createCollectionIfAbsent(collection.name());
        }
        MongoCollection<Document> mongoCollection = template.getCollection(collection.name());
        List<Document> existing = mongoCollection.listIndexes().into(new java.util.ArrayList<>());
        for (IndexSchemaContract.IndexSpec specification : collection.indexes()) {
            Optional<Document> matching = existing.stream().filter(index -> specification.name().equals(index.getString("name"))).findFirst();
            if (matching.isEmpty()) {
                mongoCollection.createIndex(new Document(specification.keys()), options(specification));
            } else if (!compatible(matching.orElseThrow(), specification)) {
                throw new IndexSchemaConflictException("conflicting index " + collection.name() + "." + specification.name());
            }
        }
    }

    private void createCollectionIfAbsent(String collectionName) {
        try {
            template.createCollection(collectionName);
            collectionCreationGate.afterCollectionCreated();
        } catch (MongoCommandException exception) {
            if (exception.getErrorCode() != 48) {
                throw exception;
            }
        } catch (DataAccessException exception) {
            if (!hasNamespaceExistsCause(exception)) {
                throw exception;
            }
        }
    }

    private static boolean hasNamespaceExistsCause(DataAccessException exception) {
        Throwable current = exception;
        while (true) {
            if (current instanceof MongoCommandException commandException) {
                return commandException.getErrorCode() == 48;
            }
            Optional<Throwable> cause = Optional.ofNullable(current.getCause());
            if (cause.isEmpty()) {
                return false;
            }
            current = cause.orElseThrow();
        }
    }

    @FunctionalInterface
    interface CollectionCreationGate {
        void afterCollectionExistsCheck();
        default void afterCollectionCreated() { }
    }

    private static IndexOptions options(IndexSchemaContract.IndexSpec specification) {
        IndexOptions result = new IndexOptions().name(specification.name()).unique(specification.unique());
        if (!specification.partialFilter().isEmpty()) { result.partialFilterExpression(new Document(specification.partialFilter())); }
        return result;
    }

    private static boolean compatible(Document existing, IndexSchemaContract.IndexSpec specification) {
        Document key = existing.get("key", Document.class);
        boolean unique = Boolean.TRUE.equals(existing.getBoolean("unique", false));
        Document partial = existing.get("partialFilterExpression", Document.class);
        Map<String, Object> actualPartial = Optional.ofNullable(partial).map(MongoIndexSchemaWriter::canonicalFilter).orElse(Map.of());
        return new Document(specification.keys()).equals(key) && unique == specification.unique()
                && actualPartial.equals(specification.partialFilter());
    }

    private static Map<String, Object> canonicalFilter(Document filter) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Document document && document.size() == 1 && document.containsKey("$eq")) {
                result.put(entry.getKey(), document.get("$eq"));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(result);
    }
}
