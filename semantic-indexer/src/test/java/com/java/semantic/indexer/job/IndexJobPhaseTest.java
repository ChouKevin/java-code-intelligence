package com.java.semantic.indexer.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexJobPhaseTest {

    @Test
    void exposes_the_asynchronous_lifecycle_phases() {
        assertThat(IndexJobPhase.valueOf("ACCEPTED")).isEqualTo(IndexJobPhase.ACCEPTED);
        assertThat(IndexJobPhase.valueOf("COMPLETE")).isEqualTo(IndexJobPhase.COMPLETE);
    }
}
