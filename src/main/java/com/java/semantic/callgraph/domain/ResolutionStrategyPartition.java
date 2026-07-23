package com.java.semantic.callgraph.domain;

import java.util.Map;

public final class ResolutionStrategyPartition {

    private static final Map<ResolutionStrategy, ResolutionCategory> CATEGORIES = Map.ofEntries(
            Map.entry(ResolutionStrategy.JDT_CALL_HIERARCHY, ResolutionCategory.RESOLVED_ANALYZABLE),
            Map.entry(ResolutionStrategy.JDT_DEFINITION_FALLBACK, ResolutionCategory.RESOLVED_ANALYZABLE),
            Map.entry(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER, ResolutionCategory.RESOLVED_ANALYZABLE),
            Map.entry(ResolutionStrategy.SPRING_BEAN_BY_PRIMARY, ResolutionCategory.RESOLVED_ANALYZABLE),
            Map.entry(ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION, ResolutionCategory.RESOLVED_ANALYZABLE),
            Map.entry(ResolutionStrategy.MYBATIS_MAPPER, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.LOMBOK_GENERATED, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.SPRING_DATA_REPOSITORY, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.EXTERNAL_LIBRARY, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.FEIGN_CLIENT, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.BUSINESS_READ_FORBIDDEN, ResolutionCategory.RESOLVED_OPAQUE),
            Map.entry(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES, ResolutionCategory.UNRESOLVED_GUESS),
            Map.entry(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, ResolutionCategory.UNRESOLVED_GUESS));

    private ResolutionStrategyPartition() {
    }

    public static ResolutionCategory categoryOf(ResolutionStrategy strategy) {
        return CATEGORIES.get(strategy);
    }

    public static boolean isGuessed(ResolutionStrategy strategy) {
        return categoryOf(strategy) != ResolutionCategory.RESOLVED_ANALYZABLE;
    }
}
