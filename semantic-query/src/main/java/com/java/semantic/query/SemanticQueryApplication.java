package com.java.semantic.query;

import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.CurrentSourceQueryService;
import com.java.semantic.query.application.CurrentSymbolQueryService;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.PublishedCallGraphService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.PublishedSourceToolService;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.query.config.SemanticQueryProperties;
import com.java.semantic.query.store.MongoIndexSchemaVerifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootApplication(scanBasePackages = {"com.java.semantic.query", "com.java.semantic.api", "com.java.semantic.mcp"})
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
    CurrentSourceQueryService currentSourceQueryService(MongoTemplate template, CurrentGenerationSelector selector,
                                                         SemanticQueryProperties properties) {
        return new CurrentSourceQueryService(template, selector, properties.storageTimeout());
    }

    @Bean
    CurrentSymbolQueryService currentSymbolQueryService(MongoTemplate template, CurrentGenerationSelector selector,
                                                         SemanticQueryProperties properties) {
        return new CurrentSymbolQueryService(template, selector, properties.storageTimeout());
    }

    @Bean
    CodeFactReadService codeFactReadService(MongoTemplate template, CurrentGenerationSelector selector, SemanticQueryProperties properties) {
        return new CodeFactReadService(template, selector, properties.storageTimeout());
    }

    @Bean
    CodeFactSearchService codeFactSearchService(MongoTemplate template, CurrentGenerationSelector selector, SemanticQueryProperties properties) {
        return new CodeFactSearchService(template, selector, properties.storageTimeout());
    }

    @Bean
    PublishedDiscoveryQueryService publishedDiscoveryQueryService(MongoTemplate template, CurrentGenerationSelector selector,
                                                                   SemanticQueryProperties properties) {
        return new PublishedDiscoveryQueryService(template, selector, properties.storageTimeout());
    }

    @Bean
    PublishedEntryPointQueryService publishedEntryPointQueryService(MongoTemplate template, CurrentGenerationSelector selector,
                                                                     SemanticQueryProperties properties) {
        return new PublishedEntryPointQueryService(template, selector, properties.storageTimeout());
    }

    @Bean
    PublishedRelationQueryService publishedRelationQueryService(MongoTemplate template, CurrentGenerationSelector selector,
                                                                SemanticQueryProperties properties) {
        return new PublishedRelationQueryService(template, selector, properties.storageTimeout());
    }

    @Bean
    PublishedCallGraphService publishedCallGraphService(MongoTemplate template, CurrentGenerationSelector selector,
                                                        SemanticQueryProperties properties) {
        return new PublishedCallGraphService(template, selector, properties.storageTimeout());
    }

    @Bean
    PublishedSourceToolService publishedSourceToolService(CurrentSourceQueryService sourceQueryService,
                                                          CodeFactReadService codeFactReadService) {
        return new PublishedSourceToolService(sourceQueryService, codeFactReadService);
    }

    @Bean
    SemanticQueryFacade semanticQueryFacade(CurrentRepositoryQueryService repositoryQueryService,
                                            CodeFactSearchService codeFactSearchService,
                                            PublishedSourceToolService sourceToolService,
                                            CodeFactReadService codeFactReadService,
                                            PublishedDiscoveryQueryService discoveryQueryService,
                                            PublishedEntryPointQueryService entryPointQueryService) {
        return new SemanticQueryFacade(repositoryQueryService, codeFactSearchService, sourceToolService, codeFactReadService,
                discoveryQueryService, entryPointQueryService);
    }

    @Bean
    ApplicationRunner semanticIndexSchemaGate(MongoTemplate template, SemanticQueryProperties properties) {
        return arguments -> new MongoIndexSchemaVerifier(template, properties.storageTimeout()).verify();
    }

}
