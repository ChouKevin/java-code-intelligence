package com.java.semantic.callgraph.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionStrategyPartitionTest {

    @Test
    void should_classify_every_strategy_when_contract_is_enumerated() {
        Map<ResolutionStrategy, ResolutionCategory> expectedCategories = Map.ofEntries(
                Map.entry(ResolutionStrategy.JDT_CALL_HIERARCHY, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.JDT_DEFINITION_FALLBACK, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.SPRING_BEAN_BY_QUALIFIER, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.SPRING_BEAN_BY_PRIMARY, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.SPRING_SINGLE_IMPLEMENTATION, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.MYBATIS_MAPPER, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.LOMBOK_GENERATED, ResolutionCategory.RESOLVED_ANALYZABLE),
                Map.entry(ResolutionStrategy.EXTERNAL_LIBRARY, ResolutionCategory.RESOLVED_OPAQUE),
                Map.entry(ResolutionStrategy.FEIGN_CLIENT, ResolutionCategory.RESOLVED_OPAQUE),
                Map.entry(ResolutionStrategy.BUSINESS_READ_FORBIDDEN, ResolutionCategory.RESOLVED_OPAQUE),
                Map.entry(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES, ResolutionCategory.UNRESOLVED_GUESS),
                Map.entry(ResolutionStrategy.DATA_ACCESS_WITHOUT_EVIDENCE, ResolutionCategory.UNRESOLVED_GUESS),
                Map.entry(ResolutionStrategy.UNRESOLVED_TARGET, ResolutionCategory.UNRESOLVED_GUESS));

        assertThat(expectedCategories).hasSize(ResolutionStrategy.values().length);
        expectedCategories.forEach((strategy, category) ->
                assertThat(ResolutionStrategyPartition.categoryOf(strategy)).isEqualTo(category));
    }

    @Test
    void should_treat_opaque_and_unresolved_behavior_as_unverified_when_llm_gate_is_asked() {
        assertThat(ResolutionStrategyPartition.isGuessed(ResolutionStrategy.EXTERNAL_LIBRARY)).isTrue();
        assertThat(ResolutionStrategyPartition.isGuessed(ResolutionStrategy.BUSINESS_READ_FORBIDDEN)).isTrue();
        assertThat(ResolutionStrategyPartition.isGuessed(ResolutionStrategy.UNRESOLVED_TARGET)).isTrue();
        assertThat(ResolutionStrategyPartition.isGuessed(ResolutionStrategy.LOMBOK_GENERATED)).isFalse();
    }
}
