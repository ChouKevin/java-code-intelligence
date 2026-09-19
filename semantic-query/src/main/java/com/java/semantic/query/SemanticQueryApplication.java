package com.java.semantic.query;

import com.java.semantic.model.index.SemanticAnalysisEvidence;
import java.util.Optional;
import org.bson.Document;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.application.SelectedGenerationGuard;
import com.java.semantic.query.application.ReviewGenerationSelector;
import com.java.semantic.query.application.ReviewManifestReadService;
import com.java.semantic.query.application.GitEvidenceReadService;
import com.java.semantic.query.application.CurrentRepositoryQueryService;
import com.java.semantic.query.application.CurrentSourceQueryService;
import com.java.semantic.query.application.CurrentSymbolQueryService;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.CodeFactSearchService;
import com.java.semantic.query.application.PublishedDiscoveryQueryService;
import com.java.semantic.query.application.PublishedEntryPointQueryService;
import com.java.semantic.query.application.PublishedRelationQueryService;
import com.java.semantic.query.application.SourceSliceService;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.application.SelectedSemanticQueryService;
import com.java.semantic.query.application.ReviewQueryFacade;
import com.java.semantic.query.application.SourceIndexCoverageReader;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.GitEvidenceProperties;
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
@EnableConfigurationProperties({SemanticQueryProperties.class, ReadPolicyProperties.class, GitEvidenceProperties.class})
public class SemanticQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticQueryApplication.class, args);
    }

    @Bean
    ConfiguredReadPolicy configuredReadPolicy(ReadPolicyProperties properties, GitEvidenceProperties gitEvidenceProperties) {
        return new ConfiguredReadPolicy(properties, gitEvidenceProperties);
    }

    @Bean
    CurrentGenerationSelector currentGenerationSelector(MongoTemplate template, ConfiguredReadPolicy policy,
                                                         SemanticQueryProperties properties) {
        return new CurrentGenerationSelector(template, policy, properties.storageTimeout());
    }

    @Bean
    SelectedGenerationGuard selectedGenerationGuard(MongoTemplate template, ConfiguredReadPolicy policy,
                                                   SemanticQueryProperties properties) {
        return new SelectedGenerationGuard(template, policy, properties.storageTimeout());
    }

    @Bean
    ReviewManifestReadService reviewManifestReadService(MongoTemplate template, ConfiguredReadPolicy policy,
                                                        SemanticQueryProperties properties) {
        return new ReviewManifestReadService(template, policy, properties.storageTimeout());
    }

    @Bean
    ReviewGenerationSelector reviewGenerationSelector(ReviewManifestReadService reviews, SelectedGenerationGuard guard) {
        return new ReviewGenerationSelector(reviews, guard);
    }

    @Bean
    CurrentRepositoryQueryService currentRepositoryQueryService(CurrentGenerationSelector selector) {
        return new CurrentRepositoryQueryService(selector);
    }

    @Bean
    CurrentSourceQueryService currentSourceQueryService(MongoTemplate template, SelectedGenerationGuard guard,
                                                         SemanticQueryProperties properties) {
        return new CurrentSourceQueryService(template, guard, properties.storageTimeout());
    }

    @Bean
    CurrentSymbolQueryService currentSymbolQueryService(MongoTemplate template, SelectedGenerationGuard guard,
                                                         SemanticQueryProperties properties) {
        return new CurrentSymbolQueryService(template, guard, properties.storageTimeout());
    }

    @Bean
    CodeFactReadService codeFactReadService(MongoTemplate template, SelectedGenerationGuard guard, SemanticQueryProperties properties) {
        return new CodeFactReadService(template, guard, properties.storageTimeout());
    }

    @Bean
    CodeFactSearchService codeFactSearchService(MongoTemplate template, SelectedGenerationGuard guard, SemanticQueryProperties properties) {
        return new CodeFactSearchService(template, guard, properties.storageTimeout());
    }

    @Bean
    PublishedDiscoveryQueryService publishedDiscoveryQueryService(MongoTemplate template, SelectedGenerationGuard guard,
                                                                   SemanticQueryProperties properties) {
        return new PublishedDiscoveryQueryService(template, guard, properties.storageTimeout());
    }

    @Bean
    PublishedEntryPointQueryService publishedEntryPointQueryService(MongoTemplate template, SelectedGenerationGuard guard,
                                                                     SemanticQueryProperties properties) {
        return new PublishedEntryPointQueryService(template, guard, properties.storageTimeout());
    }

    @Bean
    PublishedRelationQueryService publishedRelationQueryService(MongoTemplate template, SelectedGenerationGuard guard,
                                                                SemanticQueryProperties properties) {
        return new PublishedRelationQueryService(template, guard, properties.storageTimeout());
    }

    @Bean
    SourceSliceService sourceSliceService(CurrentSourceQueryService sourceQueryService,
                                          CodeFactReadService codeFactReadService) {
        return new SourceSliceService(sourceQueryService, codeFactReadService);
    }

    @Bean
    SelectedSemanticQueryService selectedSemanticQueryService(CodeFactSearchService codeFactSearchService,
                                                              SourceSliceService sourceSliceService,
                                                              CodeFactReadService codeFactReadService,
                                                              PublishedDiscoveryQueryService discoveryQueryService,
                                                              PublishedEntryPointQueryService entryPointQueryService,
                                                              PublishedRelationQueryService relationQueryService) {
        return new SelectedSemanticQueryService(codeFactSearchService, sourceSliceService, codeFactReadService,
                discoveryQueryService, entryPointQueryService, relationQueryService);
    }

    @Bean
    SourceIndexCoverageReader sourceIndexCoverageReader(MongoTemplate template, SemanticQueryProperties properties) {
        return new SourceIndexCoverageReader(template, properties.storageTimeout());
    }

    @Bean
    ReviewQueryFacade reviewQueryFacade(ReviewGenerationSelector selector, ReviewManifestReadService manifests,
                                        SelectedSemanticQueryService selectedQueries, SelectedGenerationGuard guard,
                                        SourceIndexCoverageReader coverageReader) {
        return new ReviewQueryFacade(selector, manifests, selectedQueries, guard, coverageReader);
    }

    @Bean
    SemanticQueryFacade semanticQueryFacade(CurrentGenerationSelector selector,
                                            SelectedSemanticQueryService selectedQueries,
                                            CurrentRepositoryQueryService repositoryQueryService,
                                            GitEvidenceReadService gitEvidenceReadService) {
        return new SemanticQueryFacade(selector, selectedQueries, repositoryQueryService, gitEvidenceReadService);
    }

    @Bean
    GitEvidenceReadService gitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy policy,
                                                  SemanticQueryProperties properties, ReviewManifestReadService reviews) {
        return new GitEvidenceReadService(template, policy, properties.storageTimeout(), reviews);
    }
    @Bean
    ApplicationRunner semanticIndexSchemaGate(MongoTemplate template, SemanticQueryProperties properties) {
        return arguments -> new MongoIndexSchemaVerifier(template, properties.storageTimeout()).verify();
    }

    @Bean
    static BeanPostProcessor mongoMappingConverterConfiguration() {
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
    MongoCustomConversions queryMongoCustomConversions() {
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

}
