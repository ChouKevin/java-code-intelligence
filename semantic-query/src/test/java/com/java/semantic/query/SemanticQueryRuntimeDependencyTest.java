package com.java.semantic.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class SemanticQueryRuntimeDependencyTest {

    @Test
    void provides_the_spring_ai_json_mapper_runtime_dependency() {
        assertThatCode(() -> Class.forName("org.springframework.ai.util.JacksonUtils"))
                .doesNotThrowAnyException();
    }
}
