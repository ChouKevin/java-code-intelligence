package com.java.semantic.query;

import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.CurrentSourceQueryService;
import com.java.semantic.query.application.CurrentSymbolQueryService;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.query.config.SemanticQueryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootApplication
@EnableConfigurationProperties({SemanticQueryProperties.class, ReadPolicyProperties.class})
public class SemanticQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticQueryApplication.class, args);
    }

    @Bean
    ConfiguredReadPolicy configuredReadPolicy(ReadPolicyProperties properties) {
        return new ConfiguredReadPolicy(properties);
    }

    @Bean
    CurrentGenerationSelector currentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy policy,
                                                         SemanticQueryProperties properties) {
        return new CurrentGenerationSelector(template, policy, properties.storageTimeout());
    }

    @Bean
    CurrentRepositoryQueryService currentRepositoryQueryService(CurrentGenerationSelector selector) {
        return new CurrentRepositoryQueryService(selector);
    }

    @Bean
    CurrentSourceQueryService currentSourceQueryService(MongoTemplate template, CurrentGenerationSelector selector) {
        return new CurrentSourceQueryService(template, selector);
    }

    @Bean
    CurrentSymbolQueryService currentSymbolQueryService(MongoTemplate template, CurrentGenerationSelector selector) {
        return new CurrentSymbolQueryService(template, selector);
    }

}
