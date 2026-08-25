package com.java.semantic.indexer.store;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaBootstrapCommandTest {

    @Test
    void runs_the_versioned_bootstrap_once_and_returns_its_fingerprint() {
        AtomicInteger calls = new AtomicInteger();
        SchemaBootstrapCommand command = new SchemaBootstrapCommand(() -> {
            calls.incrementAndGet();
            return "schema-v1-fingerprint";
        });

        assertThat(command.run()).isEqualTo("schema-v1-fingerprint");
        assertThat(calls).hasValue(1);
    }
}
