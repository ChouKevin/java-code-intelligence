package com.java.semantic.config;

import com.java.semantic.callgraph.application.CanonicalTargetProjection;
import com.java.semantic.callgraph.application.DataAccessEvidence;
import com.java.semantic.callgraph.application.DirectCallRelationshipResolver;
import com.java.semantic.callgraph.application.GeneratedMemberEvidence;
import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.ImplementationCandidateFactory;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SpringImplementationSelector;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryApplicationService;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.application.ExactMethodDeclarationResolver;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        IncomingGraphProperties.class,
        OutgoingGraphProperties.class,
        ReadPolicyProperties.class,
        ImplementationDiscoveryProperties.class
})
public class SemanticAnalysisConfiguration {

    @Bean
    public ReadPolicy readPolicy(ReadPolicyProperties properties) {
        return new ConfiguredReadPolicy(properties);
    }

    @Bean
    @ConditionalOnBean(JavaSemanticService.class)
    public DirectCallRelationshipResolver directCallRelationshipResolver(
            JavaSemanticService semanticService) {
        return new DirectCallRelationshipResolver(
                semanticService, new SpringImplementationSelector(), new CanonicalTargetProjection());
    }

    @Bean
    @ConditionalOnBean(JavaSemanticService.class)
    public SemanticCallGraphBuilder semanticCallGraphBuilder(
            DirectCallRelationshipResolver relationshipResolver) {
        return new SemanticCallGraphBuilder(relationshipResolver, new GeneratedMemberEvidence(), new DataAccessEvidence());
    }

    @Bean
    @ConditionalOnBean(JavaSemanticService.class)
    public IncomingSemanticCallGraphBuilder incomingSemanticCallGraphBuilder(
            JavaSemanticService semanticService,
            DirectCallRelationshipResolver relationshipResolver) {
        return new IncomingSemanticCallGraphBuilder(
                semanticService,
                relationshipResolver,
                new CanonicalTargetProjection(),
                new DataAccessEvidence());
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            SyntaxExtractionService.class,
            JavaSemanticService.class
    })
    public MethodImplementationDiscoveryApplicationService methodImplementationDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            JavaSemanticService semanticService,
            ImplementationDiscoveryProperties properties) {
        return new MethodImplementationDiscoveryApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                new ExactMethodDeclarationResolver(),
                semanticService,
                new CanonicalTargetProjection(),
                new ImplementationCandidateFactory(),
                properties.candidateLimit());
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            SyntaxExtractionService.class,
            JavaSemanticService.class
    })
    public SemanticAnalysisApplicationService semanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder outgoingBuilder,
            IncomingSemanticCallGraphBuilder incomingBuilder,
            OutgoingGraphProperties outgoingGraphProperties,
            IncomingGraphProperties incomingGraphProperties) {
        return new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                new ExactMethodDeclarationResolver(),
                semanticService,
                outgoingBuilder,
                incomingBuilder,
                outgoingGraphProperties,
                incomingGraphProperties);
    }
}
