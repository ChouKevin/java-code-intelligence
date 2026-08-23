package com.java.semantic.callgraph.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallSiteRangeTest {

    @Test
    void should_reject_an_empty_or_reversed_half_open_range() {
        assertThatThrownBy(() -> new CallSiteRange("Source.java", 2, 3, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallSiteRange("Source.java", 3, 1, 2, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
