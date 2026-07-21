package com.java.semantic.config;

import com.java.semantic.callgraph.application.CallGraphClassifier;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CallGraphDepthProperties.class, ReadPolicyProperties.class})
public class SemanticAnalysisConfiguration {

    @Bean
    public ReadPolicy readPolicy(ReadPolicyProperties properties) {
        return new ConfiguredReadPolicy(properties);
    }

    @Bean
    @ConditionalOnBean(JavaSemanticService.class)
    public SemanticCallGraphBuilder semanticCallGraphBuilder(
            JavaSemanticService semanticService,
            ReadPolicy readPolicy) {
        return new SemanticCallGraphBuilder(
                semanticService,
                new CallGraphClassifier(),
                new SpringImplementationSelector(),
                readPolicy);
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            SyntaxExtractionService.class,
            JavaSemanticService.class,
            SemanticCallGraphBuilder.class
    })
    public SemanticAnalysisApplicationService semanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder builder,
            ReadPolicy readPolicy,
            CallGraphDepthProperties depthProperties) {
        return new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                semanticService,
                builder,
                readPolicy,
                depthProperties);
    }
}
