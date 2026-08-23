package com.java.semantic.trie;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ApiRouteCandidateMatcher {

    private ApiRouteCandidateMatcher() {
    }

    static List<ApiRouteMatchReason> suggestionReasons(String queryPath, String routeTemplate) {
        List<String> query = segments(queryPath);
        List<String> route = segments(routeTemplate);
        List<ApiRouteMatchReason> reasons = new ArrayList<>();
        boolean sharedStaticSegment = false;
        boolean positionalStaticSegment = false;
        for (int routeIndex = 0; routeIndex < route.size(); routeIndex++) {
            String routeSegment = route.get(routeIndex);
            if (ApiPathNormalizer.ONE_SEGMENT_WILDCARD.equals(routeSegment)
                    || ApiPathNormalizer.REST_WILDCARD.equals(routeSegment)) {
                continue;
            }
            if (query.contains(routeSegment)) {
                sharedStaticSegment = true;
            }
            if (routeIndex < query.size() && routeSegment.equals(query.get(routeIndex))) {
                positionalStaticSegment = true;
            }
        }
        if (sharedStaticSegment) {
            reasons.add(ApiRouteMatchReason.SHARED_STATIC_SEGMENT);
        }
        if (positionalStaticSegment) {
            reasons.add(ApiRouteMatchReason.POSITIONAL_STATIC_SEGMENT);
        }
        if (query.size() == route.size()) {
            reasons.add(ApiRouteMatchReason.SAME_SEGMENT_COUNT);
        }
        return List.copyOf(reasons);
    }

    static List<ApiRouteMatchReason> lookupReasons(
            String normalizedPath, String httpMethod, ApiEntryPointRef ref) {
        List<ApiRouteMatchReason> reasons = new ArrayList<>();
        if (normalizedPath.equals(ref.routeTemplate())) {
            reasons.add(ApiRouteMatchReason.EXACT_NORMALIZED_PATH);
        } else {
            reasons.add(ApiRouteMatchReason.TEMPLATE_MATCH);
        }
        if (StringUtils.hasText(httpMethod)
                && (httpMethod.equals(ref.httpMethod()) || ApiTrieNode.METHOD_ALL.equals(ref.httpMethod()))) {
            reasons.add(ApiRouteMatchReason.HTTP_METHOD_MATCH);
        }
        return List.copyOf(reasons);
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .toList();
    }
}
