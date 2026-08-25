package com.java.semantic;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.SchemaBootstrapCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Arrays;

/** Indexer runs administrative index mutations only; read APIs and MCP live in semantic-query. */
@SpringBootApplication
public class SemanticIndexerApplication {

    public static void main(String[] args) {
        if (schemaBootstrapRequested(args)) {
            runSchemaBootstrap(args);
            return;
        }
        SpringApplication.run(SemanticIndexerApplication.class, args);
    }

    static boolean schemaBootstrapRequested(String[] args) {
        return Arrays.stream(args).anyMatch("--semantic.schema-bootstrap=true"::equals);
    }

    private static void runSchemaBootstrap(String[] args) {
        try (ConfigurableApplicationContext application = new SpringApplicationBuilder(SchemaBootstrapApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            SchemaBootstrapCommand command = new SchemaBootstrapCommand(() ->
                    application.getBean(IndexSchemaBootstrap.class).bootstrap());
            System.out.printf("schemaFingerprint=%s%n", command.run());
        }
    }

    /** Minimal context for the high-privilege schema-maintenance command: Mongo auto-configuration only. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SchemaBootstrapApplication {
        @Bean
        IndexSchemaBootstrap indexSchemaBootstrap(MongoTemplate template) {
            return new IndexSchemaBootstrap(template);
        }
    }
}
