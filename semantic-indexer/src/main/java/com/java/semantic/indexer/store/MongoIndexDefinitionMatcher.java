package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;

/** Shared exact comparison for installed Mongo indexes and the registered schema contract. */
public final class MongoIndexDefinitionMatcher {

    private MongoIndexDefinitionMatcher() { }

    public static boolean matches(Document installed, IndexSchemaContract.IndexSpec required) {
        Objects.requireNonNull(installed, "installed index is required");
        Objects.requireNonNull(required, "required index is required");
        Document key = installed.get("key", Document.class);
        boolean unique = Boolean.TRUE.equals(installed.getBoolean("unique", false));
        Document partial = installed.get("partialFilterExpression", Document.class);
        Map<String, Object> actualPartial = Optional.ofNullable(partial)
                .map(MongoIndexDefinitionMatcher::canonicalFilter)
                .orElse(Map.of());
        return new Document(required.keys()).equals(key)
                && unique == required.unique()
                && actualPartial.equals(required.partialFilter());
    }

    private static Map<String, Object> canonicalFilter(Document filter) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
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
