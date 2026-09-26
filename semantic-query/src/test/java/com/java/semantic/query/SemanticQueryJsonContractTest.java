package com.java.semantic.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticQueryJsonContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void omits_absent_optional_values_from_query_responses() {
        contextRunner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);

            assertThat(mapper.writeValueAsString(new OptionalResponse(Optional.empty())))
                    .isEqualTo("{}");
        });
    }

    private record OptionalResponse(Optional<String> packagePrefix) {
    }
}
