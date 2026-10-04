package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SourceOperationDeadlineTest {
    @Test
    void work_that_finishes_after_its_request_budget_cannot_return_a_late_success() {
        assertThatThrownBy(() -> SourceOperationDeadline.within(Duration.ofMillis(10), () -> {
            long finishAt = System.nanoTime() + Duration.ofMillis(30).toNanos();
            while (System.nanoTime() - finishAt < 0) Thread.onSpinWait();
            return "late result";
        })).isInstanceOfSatisfying(SourceQueryException.class, error ->
                assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_TIMEOUT));

        assertThat(SourceOperationDeadline.within(Duration.ofSeconds(2), () -> "next request"))
                .isEqualTo("next request");
    }
}
