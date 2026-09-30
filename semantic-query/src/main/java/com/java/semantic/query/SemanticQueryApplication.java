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
import com.java.semantic.query.application.ReadContextSelector;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.ContextDiscoveryService;
import com.java.semantic.query.application.ReviewManifestReadService;
import com.java.semantic.query.application.GitEvidenceReadService;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.application.SelectedSemanticQueryService;
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
    ReadContextSelector readContextSelector(CurrentGenerationSelector current, ReviewManifestReadService reviews,
            SelectedGenerationGuard guard, ConfiguredReadPolicy policy) {
        return new ReadContextSelector(current, reviews, guard, policy);
    }

    @Bean
    ContextDiscoveryService contextDiscoveryService(MongoTemplate template, ConfiguredReadPolicy policy,
            SemanticQueryProperties properties, CurrentGenerationSelector current, ReadContextSelector contexts,
            ReviewManifestReadService reviews) {
        return new ContextDiscoveryService(template, policy, properties.storageTimeout(), current, contexts, reviews);
    }

    @Bean
    CodeFactReadService codeFactReadService(MongoTemplate template, SelectedGenerationGuard guard, SemanticQueryProperties properties) {
        return new CodeFactReadService(template, guard, properties.storageTimeout());
    }

    @Bean
    SelectedSemanticQueryService selectedSemanticQueryService(MongoTemplate template, SelectedGenerationGuard guard,
            SemanticQueryProperties properties, CodeFactReadService facts) {
        return new SelectedSemanticQueryService(template, guard, properties.storageTimeout(), facts);
    }

    @Bean
    GitEvidenceReadService gitEvidenceReadService(MongoTemplate template, ConfiguredReadPolicy policy,
            SemanticQueryProperties properties, CodeFactReadService facts) {
        return new GitEvidenceReadService(template, policy, properties.storageTimeout(), facts);
    }

    @Bean
    SemanticQueryFacade semanticQueryFacade(ContextDiscoveryService discovery, ReadContextSelector contexts,
            SelectedSemanticQueryService semantic, GitEvidenceReadService evidence) {
        return new SemanticQueryFacade(discovery, contexts, semantic, evidence);
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
