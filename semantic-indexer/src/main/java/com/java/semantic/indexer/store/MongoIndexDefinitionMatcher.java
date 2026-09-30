package com.java.semantic.indexer.store;

import com.java.semantic.model.index.IndexSchemaContract;
import java.util.LinkedHashMap;
import java.util.List;
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
        return Objects.nonNull(key)
                && List.copyOf(key.entrySet()).equals(List.copyOf(required.keys().entrySet()))
                && unique == required.unique()
                && sameFilter(actualPartial, required.partialFilter());
    }

    private static boolean sameFilter(Map<?, ?> actual, Map<?, ?> required) {
        if (actual.size() != required.size()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : actual.entrySet()) {
            if (!required.containsKey(entry.getKey())) {
                return false;
            }
            Object actualValue = entry.getValue();
            Object requiredValue = required.get(entry.getKey());
            // BSON Document equality is not Map equality, even for identical nested operators.
            if (actualValue instanceof Map<?, ?> actualMap && requiredValue instanceof Map<?, ?> requiredMap) {
                if (!sameFilter(actualMap, requiredMap)) {
                    return false;
                }
            } else if (!Objects.equals(actualValue, requiredValue)) {
                return false;
            }
        }
        return true;
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
