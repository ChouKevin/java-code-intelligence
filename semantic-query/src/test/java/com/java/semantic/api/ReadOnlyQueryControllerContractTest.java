package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadOnlyQueryControllerContractTest {

    @Test
    void publishes_exactly_the_twelve_approved_application_routes() throws Exception {
        Class<?> controller = Class.forName("com.java.semantic.api.SemanticQueryController");
        RequestMapping rootMapping = controller.getAnnotation(RequestMapping.class);

        assertEquals(Set.of("/api/v1"), Set.copyOf(Arrays.asList(rootMapping.value())));
        assertEquals(Set.of(
                "GET /repositories",
                "GET /repositories/{repositoryId}",
                "POST /search-code",
                "POST /fact-source",
                "POST /entry-points",
                "POST /api-routes",
                "POST /event-listeners",
                "POST /type-members",
                "POST /method-implementations",
                "POST /references",
                "POST /callers",
                "POST /callees"), operationRoutes(controller));
    }

    private static Set<String> operationRoutes(Class<?> controller) {
        Set<String> routes = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            Optional.ofNullable(method.getAnnotation(GetMapping.class))
                    .ifPresent(mapping -> Arrays.stream(mapping.value()).map(path -> "GET " + path).forEach(routes::add));
            Optional.ofNullable(method.getAnnotation(PostMapping.class))
                    .ifPresent(mapping -> Arrays.stream(mapping.value()).map(path -> "POST " + path).forEach(routes::add));
        }
        return Set.copyOf(routes);
    }
}
