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
    void publishes_exactly_the_thirty_approved_application_routes() throws Exception {
        Class<?> semanticController = Class.forName("com.java.semantic.api.SemanticQueryController");
        Class<?> reviewController = Class.forName("com.java.semantic.api.ReviewQueryController");

        assertEquals(Set.of("/api/v1"), Set.copyOf(Arrays.asList(semanticController.getAnnotation(RequestMapping.class).value())));
        assertEquals(Set.of("/api/v1"), Set.copyOf(Arrays.asList(reviewController.getAnnotation(RequestMapping.class).value())));
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
                "POST /callees",
                "POST /git/branches",
                "POST /git/commits",
                "POST /git/comparisons",
                "POST /git/file-diff",
                "POST /git/files",
                "POST /git/file",
                "POST /git/search",
                "GET /repositories/{repositoryId}/reviews/{reviewId}",
                "POST /reviews/search-code",
                "POST /reviews/fact-source",
                "POST /reviews/entry-points",
                "POST /reviews/api-routes",
                "POST /reviews/event-listeners",
                "POST /reviews/type-members",
                "POST /reviews/method-implementations",
                "POST /reviews/references",
                "POST /reviews/callers",
                "POST /reviews/callees"), operationRoutes(semanticController, reviewController));
    }

    private static Set<String> operationRoutes(Class<?>... controllers) {
        Set<String> routes = new LinkedHashSet<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                Optional.ofNullable(method.getAnnotation(GetMapping.class))
                        .ifPresent(mapping -> Arrays.stream(mapping.value()).map(path -> "GET " + path).forEach(routes::add));
                Optional.ofNullable(method.getAnnotation(PostMapping.class))
                        .ifPresent(mapping -> Arrays.stream(mapping.value()).map(path -> "POST " + path).forEach(routes::add));
            }
        }
        return Set.copyOf(routes);
    }
}
