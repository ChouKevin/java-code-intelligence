package com.java.semantic.trie;

import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

final class ApiRouteCandidateMatcher {

    private ApiRouteCandidateMatcher() {
    }

    static int score(String queryPath, String routeTemplate) {
        List<String> query = segments(queryPath);
        List<String> route = segments(routeTemplate);
        int staticMatches = 0;
        int positionalMatches = 0;
        for (int routeIndex = 0; routeIndex < route.size(); routeIndex++) {
            String routeSegment = route.get(routeIndex);
            if (ApiPathNormalizer.ONE_SEGMENT_WILDCARD.equals(routeSegment)
                    || ApiPathNormalizer.REST_WILDCARD.equals(routeSegment)) {
                continue;
            }
            if (query.contains(routeSegment)) {
                staticMatches++;
            }
            if (routeIndex < query.size() && routeSegment.equals(query.get(routeIndex))) {
                positionalMatches++;
            }
        }
        if (staticMatches == 0) {
            return -1;
        }
        int sameLengthBonus = query.size() == route.size() ? 4 : 0;
        return staticMatches * 3 + positionalMatches * 5 + sameLengthBonus;
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/"))
                .filter(StringUtils::hasText)
                .toList();
    }
}
