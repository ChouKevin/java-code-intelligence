package com.java.semantic.query.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.query.application.IndexNotReadyException;
import com.java.semantic.query.application.SemanticIndexUnavailableException;
import com.mongodb.MongoException;
import com.mongodb.client.ListCollectionsIterable;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Read-only readiness check; it deliberately performs no schema mutation. */
public final class MongoIndexSchemaVerifier {
    private final MongoTemplate template;
    private final Duration storageTimeout;

    public MongoIndexSchemaVerifier(MongoTemplate template, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public String verify() {
        try {
            for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) {
                if (!collectionExists(collection.name())) { throw new IndexNotReadyException(); }
                List<Document> indexes = template.getCollection(collection.name()).listIndexes()
                        .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS).into(new java.util.ArrayList<>());
                for (IndexSchemaContract.IndexSpec expected : collection.indexes()) {
                    Document actual = indexes.stream().filter(index -> expected.name().equals(index.get("name", String.class))).findFirst().orElse(null);
                    if (Objects.isNull(actual) || !compatible(actual, expected)) { throw new IndexNotReadyException(); }
                }
            }
            return IndexSchemaContract.fingerprint();
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        } catch (IndexNotReadyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IndexNotReadyException();
        }
    }

    private boolean collectionExists(String collectionName) {
        ListCollectionsIterable<Document> collections = template.getDb().listCollections().filter(new Document("name", collectionName))
                .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
        return Objects.nonNull(collections.first());
    }

    private static boolean compatible(Document actual, IndexSchemaContract.IndexSpec expected) {
        Object storedKey = actual.get("key");
        if (!(storedKey instanceof Document key) || !orderedKeys(expected.keys(), key)) { return false; }
        boolean unique = Boolean.TRUE.equals(actual.getBoolean("unique", false));
        Object storedPartial = actual.get("partialFilterExpression");
        if (Objects.nonNull(storedPartial) && !(storedPartial instanceof Document)) { return false; }
        Document partial = (Document) storedPartial;
        Map<String, Object> actualFilter = Objects.isNull(partial) ? Map.of() : canonicalFilter(partial);
        return expected.unique() == unique && expected.partialFilter().equals(actualFilter);
    }

    private static boolean orderedKeys(Map<String, Integer> expected, Document actual) {
        if (expected.size() != actual.size()) { return false; }
        java.util.Iterator<Map.Entry<String, Integer>> expectedEntries = expected.entrySet().iterator();
        java.util.Iterator<Map.Entry<String, Object>> actualEntries = actual.entrySet().iterator();
        while (expectedEntries.hasNext()) {
            Map.Entry<String, Integer> expectedEntry = expectedEntries.next();
            Map.Entry<String, Object> actualEntry = actualEntries.next();
            if (!expectedEntry.getKey().equals(actualEntry.getKey()) || !expectedEntry.getValue().equals(actualEntry.getValue())) { return false; }
        }
        return true;
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
