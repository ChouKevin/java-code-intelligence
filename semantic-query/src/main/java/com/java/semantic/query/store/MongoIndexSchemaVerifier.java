package com.java.semantic.query.store;

import com.java.semantic.model.index.IndexSchemaContract;
import com.mongodb.MongoException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only readiness check; it deliberately performs no schema mutation. */
public final class MongoIndexSchemaVerifier {
    private final MongoTemplate template;
    public MongoIndexSchemaVerifier(MongoTemplate template) { this.template = Objects.requireNonNull(template, "mongo template is required"); }
    public String verify() {
        try {
            for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) {
                if (!template.collectionExists(collection.name())) { throw new IndexNotReadyException("missing index collection " + collection.name()); }
                for (IndexSchemaContract.IndexSpec index : collection.indexes()) {
                    Optional<Document> actual = template.getCollection(collection.name()).listIndexes().into(new java.util.ArrayList<Document>()).stream()
                            .filter(candidate -> index.name().equals(candidate.getString("name"))).findFirst();
                    if (actual.isEmpty() || !compatible(actual.orElseThrow(), index)) { throw new IndexNotReadyException("schema mismatch " + collection.name() + "." + index.name()); }
                }
            }
            return IndexSchemaContract.fingerprint();
        } catch (DataAccessResourceFailureException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        } catch (MongoException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    private static boolean compatible(Document actual, IndexSchemaContract.IndexSpec expected) {
        Document key = actual.get("key", Document.class);
        boolean unique = Boolean.TRUE.equals(actual.getBoolean("unique", false));
        Document partial = actual.get("partialFilterExpression", Document.class);
        Map<String, Object> actualFilter = Optional.ofNullable(partial).map(MongoIndexSchemaVerifier::canonicalFilter).orElse(Map.of());
        return new Document(expected.keys()).equals(key) && expected.unique() == unique && expected.partialFilter().equals(actualFilter);
    }

    private static Map<String, Object> canonicalFilter(Document filter) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Document document && document.size() == 1 && document.containsKey("$eq")) { result.put(entry.getKey(), document.get("$eq")); }
            else { result.put(entry.getKey(), value); }
        }
        return Map.copyOf(result);
    }
}
