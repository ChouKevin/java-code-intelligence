package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReadOnlyQueryControllerContractTest {

    @Test
    void publishes_all_read_only_families_but_no_removed_concept_route() {
        Set<String> postPaths = Arrays.stream(ReadOnlyQueryController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of(
                "/analyses/call-graphs/outgoing", "/analyses/call-graphs/incoming",
                "/api-routes/lookup", "/api-routes/suggest", "/discovery/event-listeners",
                "/discovery/method-implementations", "/discovery/type-members", "/discovery/internal-references",
                "/discovery/source-symbols/resolve", "/discovery/source-segment", "/discovery/method-source",
                "/discovery/evidence-source"), postPaths);
        assertFalse(postPaths.stream().anyMatch(path -> path.contains("concept")));
    }
}
