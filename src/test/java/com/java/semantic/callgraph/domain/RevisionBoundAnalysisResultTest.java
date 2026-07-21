package com.java.semantic.callgraph.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionBoundAnalysisResultTest {

    @Test
    void should_preserve_literal_fixture_revision_when_result_is_created() {
        RevisionBoundAnalysisResult<String> result = RevisionBoundAnalysisResult.success(
                "graph", new AnalysisMetadata("fixture-repo", Instant.EPOCH), "FIXTURE");

        assertThat(result.analyzedRevision()).isEqualTo("FIXTURE");
        assertThat(result.warnings()).hasSize(0);
        assertThat(result.errors()).hasSize(0);
    }
}
