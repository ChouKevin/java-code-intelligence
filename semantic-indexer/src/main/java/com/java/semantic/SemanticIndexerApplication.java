package com.java.semantic;

import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.SchemaBootstrapCommand;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import java.util.Arrays;
import java.util.Optional;
import org.bson.Document;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.convert.converter.Converter;

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


    @Bean
    static BeanPostProcessor indexerMongoMappingConverterConfiguration() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof MappingMongoConverter converter) {
                    converter.setMapKeyDotReplacement("__dot__");
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(MongoCustomConversions.class)
    MongoCustomConversions indexerMongoCustomConversions() {
        return semanticAnalysisEvidenceMongoCustomConversions();
    }

    public static MongoCustomConversions semanticAnalysisEvidenceMongoCustomConversions() {
        return MongoCustomConversions.create(adapter -> {
            adapter.registerConverter(new LimitationWriter());
            adapter.registerConverter(new LimitationReader());
        });
    }

    private static final class LimitationWriter implements Converter<SemanticAnalysisEvidence.Limitation, Document> {
        @Override
        public Document convert(SemanticAnalysisEvidence.Limitation limitation) {
            Document document = new Document("code", limitation.code());
            limitation.sourcePath().ifPresent(sourcePath -> document.append("sourcePath", sourcePath));
            return document;
        }
    }

    private static final class LimitationReader implements Converter<Document, SemanticAnalysisEvidence.Limitation> {
        @Override
        public SemanticAnalysisEvidence.Limitation convert(Document document) {
            return new SemanticAnalysisEvidence.Limitation(document.getString("code"),
                    Optional.ofNullable(document.get("sourcePath")).map(String.class::cast));
        }
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
