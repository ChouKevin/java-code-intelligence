package com.java.semantic.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.source.SourceReadContract.RepositoryRequest;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.query.SemanticQueryApplication;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.source.SourceRevisionCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class SourceAuthorizationStartupTest {
    @TempDir Path temp;

    @Test
    void legacy_restrictions_fail_at_real_startup_and_empty_source_allowlist_denies_discovery() throws Exception {
        Path published = Files.createDirectory(temp.resolve("published"));
        String[] base = {"semantic.query.source.published-root=" + published,
                "semantic.query.source.rg-executable=" + System.getProperty("source.test.rg", "/usr/bin/rg"),
                "spring.ai.mcp.server.enabled=false"};
        Files.writeString(published.resolve("repositories.json"),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(List.of(
                        new SourceRepositoryDescriptor("private", "Private", "main", Optional.empty()))));
        for (String old : new String[] {"forbidden-packages", "forbidden-classes", "forbidden-methods"}) {
            assertThatThrownBy(() -> boot(base, "semantic.query.read-policy." + old + "[0]=com.private.Secret"))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (ConfigurableApplicationContext context = boot(base)) {
            assertThat(context.getBean(SourceAccessProperties.class).allowedRepositories()).isEmpty();
            assertThat(context.getBean(SourceRevisionCatalog.class)
                    .listRepositories(new RepositoryRequest(Optional.empty(), 20, Optional.empty())).items()).isEmpty();
        }
    }

    private static ConfigurableApplicationContext boot(String[] properties, String... extra) {
        String[] arguments = Stream.concat(Arrays.stream(properties), Arrays.stream(extra))
                .map(property -> "--" + property).toArray(String[]::new);
        return new SpringApplicationBuilder(SemanticQueryApplication.class).web(WebApplicationType.NONE).run(arguments);
    }
}
