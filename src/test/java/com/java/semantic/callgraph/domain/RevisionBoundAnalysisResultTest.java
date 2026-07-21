package com.java.semantic.callgraph.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RevisionBoundAnalysisResultTest {

    @Test
    void should_map_only_data_when_result_has_readable_data() {
        AnalysisMetadata metadata = new AnalysisMetadata("orders", Instant.EPOCH);
        AnalysisWarning warning = new AnalysisWarning("WARN", "safe", "");
        AnalysisError error = new AnalysisError("ERROR", "safe", "");
        RevisionBoundAnalysisResult<String> source = new RevisionBoundAnalysisResult<>(
                AnalysisStatus.PARTIAL,
                "graph",
                List.of(warning),
                List.of(error),
                metadata,
                "1".repeat(40));

        RevisionBoundAnalysisResult<Integer> mapped = source.mapData(String::length);

        assertThat(mapped.status()).isEqualTo(source.status());
        assertThat(mapped.data()).isEqualTo(5);
        assertThat(mapped.warnings()).isEqualTo(source.warnings());
        assertThat(mapped.errors()).isEqualTo(source.errors());
        assertThat(mapped.metadata()).isSameAs(metadata);
        assertThat(mapped.analyzedRevision()).isEqualTo(source.analyzedRevision());
    }

    @Test
    void should_not_invoke_mapper_when_result_has_no_data() {
        AtomicBoolean invoked = new AtomicBoolean();
        RevisionBoundAnalysisResult<String> source = RevisionBoundAnalysisResult.failed(
                List.of(new AnalysisError("ERROR", "safe", "")),
                new AnalysisMetadata("orders", Instant.EPOCH),
                "FIXTURE");

        RevisionBoundAnalysisResult<Integer> mapped = source.mapData(value -> {
            invoked.set(true);
            return value.length();
        });

        assertThat(invoked).isFalse();
        assertThat(mapped.data()).isNull();
        assertThat(mapped.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(mapped.analyzedRevision()).isEqualTo("FIXTURE");
    }

    @Test
    void should_preserve_literal_fixture_revision_when_result_is_created() {
        RevisionBoundAnalysisResult<String> result = RevisionBoundAnalysisResult.success(
                "graph", new AnalysisMetadata("fixture-repo", Instant.EPOCH), "FIXTURE");

        assertThat(result.analyzedRevision()).isEqualTo("FIXTURE");
        assertThat(result.warnings()).hasSize(0);
        assertThat(result.errors()).hasSize(0);
    }
}
