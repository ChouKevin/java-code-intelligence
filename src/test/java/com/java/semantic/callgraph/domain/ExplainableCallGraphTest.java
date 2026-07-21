package com.java.semantic.callgraph.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainableCallGraphTest {

    private static final List<String> CANONICAL_KEYS = List.of(
            "zeta", "alpha", "theta", "beta", "omega", "gamma", "delta", "eta");

    @Test
    void should_preserve_caller_supplied_related_class_order_in_domain_and_json() throws Exception {
        Map<String, String> relatedClasses = new LinkedHashMap<>();
        for (String key : CANONICAL_KEYS) {
            relatedClasses.put(key, key + " source");
        }
        MethodId root = new MethodId("orders", "com.example", "Root", "run", List.of());
        FlattenedCallGraph flattened = new FlattenedCallGraph(List.of(), "run()", relatedClasses);
        ExplainableCallGraph graph = new ExplainableCallGraph(
                root, List.of(), List.of(), relatedClasses, flattened);

        assertThat(graph.relatedClasses().keySet()).containsExactlyElementsOf(CANONICAL_KEYS);
        assertThat(graph.legacyFlattened().relatedClasses().keySet()).containsExactlyElementsOf(CANONICAL_KEYS);
        String serialized = new ObjectMapper().writeValueAsString(graph);
        int previous = -1;
        for (String key : CANONICAL_KEYS) {
            int current = serialized.indexOf("\"" + key + "\"");
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }
}
