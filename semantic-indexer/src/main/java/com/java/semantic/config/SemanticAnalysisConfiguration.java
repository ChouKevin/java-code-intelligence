package com.java.semantic.config;
import com.java.semantic.model.codefact.SourceRange;

import java.util.List;

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
import com.java.semantic.repository.domain.RepositorySourceContainment;
import com.java.semantic.semantic.application.MethodImplementationDiscoveryApplicationService;
import com.java.semantic.semantic.application.InternalReferenceAnalysisCache;
import com.java.semantic.semantic.application.InternalSourceReferenceApplicationService;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.adapter.cache.CaffeineInternalReferenceAnalysisCache;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.adapter.cache.CaffeineRevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.ExactSourceDeclarationResolver;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.syntax.domain.RevisionPinnedSourceRangeReader;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.syntax.application.concept.ConceptSearchDocumentProjector;
import com.java.semantic.syntax.application.concept.ConceptSearchMatcher;
import com.java.semantic.syntax.application.concept.ConceptSearchTokenizer;
import com.java.semantic.syntax.application.concept.DeclarationConceptProvider;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.application.concept.EntryPointConceptProvider;
import com.java.semantic.syntax.application.EvidenceSourceApplicationService;
import com.java.semantic.syntax.application.concept.MapperStatementConceptProvider;
import com.java.semantic.syntax.application.concept.StructuredConceptCatalogProjector;
import com.java.semantic.syntax.application.TypeMemberDiscoveryApplicationService;
import com.java.semantic.syntax.application.concept.TypeUsageConceptProvider;
import com.java.semantic.syntax.application.SourceSymbolResolutionApplicationService;
import com.java.semantic.syntax.application.SourceSymbolResolver;
import com.java.semantic.syntax.application.SourceSegmentApplicationService;
import com.java.semantic.syntax.application.MethodSourceApplicationService;
import com.java.semantic.syntax.application.DefaultRevisionPinnedSourceRangeReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        IncomingGraphProperties.class,
        OutgoingGraphProperties.class,
        ReadPolicyProperties.class,
        ImplementationDiscoveryProperties.class,
        SourceSymbolResolutionProperties.class,
        InternalReferenceCacheProperties.class,
        RepositorySyntaxCacheProperties.class
})
public class SemanticAnalysisConfiguration {

    @Bean
    @ConditionalOnBean(SyntaxExtractionService.class)
    public RevisionBoundRepositorySyntaxProvider revisionBoundRepositorySyntaxProvider(
            SyntaxExtractionService syntaxExtractionService,
            RepositorySyntaxCacheProperties properties) {
        return new CaffeineRevisionBoundRepositorySyntaxProvider(syntaxExtractionService, properties);
    }

    @Bean
    public InternalReferenceAnalysisCache internalReferenceAnalysisCache(
            InternalReferenceCacheProperties properties) {
        return new CaffeineInternalReferenceAnalysisCache(properties);
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            ExactSourceDeclarationResolver.class,
            JavaSemanticService.class,
            RevisionBoundRepositorySyntaxProvider.class,
            InternalReferenceAnalysisCache.class
    })
    public InternalSourceReferenceApplicationService internalSourceReferenceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            ExactSourceDeclarationResolver declarationResolver,
            JavaSemanticService semanticService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            InternalReferenceAnalysisCache cache) {
        return new InternalSourceReferenceApplicationService(
                repositoryApplicationService,
                declarationResolver,
                semanticService,
                repositorySyntaxProvider,
                cache);
    }

    @Bean
    @ConditionalOnBean(RepositoryApplicationService.class)
    public RevisionPinnedSourceRangeReader revisionPinnedSourceRangeReader() {
        return new DefaultRevisionPinnedSourceRangeReader(new RepositorySourceContainment());
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            RevisionBoundRepositorySyntaxProvider.class,
            RevisionPinnedSourceRangeReader.class
    })
    public SourceSegmentApplicationService sourceSegmentApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        return new SourceSegmentApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                sourceRangeReader);
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            RevisionBoundRepositorySyntaxProvider.class,
            RevisionPinnedSourceRangeReader.class
    })
    public MethodSourceApplicationService methodSourceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        return new MethodSourceApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                new CanonicalMethodDeclarationResolver(),
                sourceRangeReader);
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public ConceptSearchTokenizer conceptSearchTokenizer() {
        return new ConceptSearchTokenizer();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public ConceptSearchMatcher conceptSearchMatcher() {
        return new ConceptSearchMatcher();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public ConceptSearchDocumentProjector conceptSearchDocumentProjector() {
        return new ConceptSearchDocumentProjector();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public DeclarationConceptProvider declarationConceptProvider() {
        return new DeclarationConceptProvider();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public TypeUsageConceptProvider typeUsageConceptProvider() {
        return new TypeUsageConceptProvider();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public EntryPointConceptProvider entryPointConceptProvider() {
        return new EntryPointConceptProvider();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public MapperStatementConceptProvider mapperStatementConceptProvider() {
        return new MapperStatementConceptProvider();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public StructuredConceptCatalogProjector structuredConceptCatalogProjector(
            DeclarationConceptProvider declarationConceptProvider,
            TypeUsageConceptProvider typeUsageConceptProvider,
            EntryPointConceptProvider entryPointConceptProvider,
            MapperStatementConceptProvider mapperStatementConceptProvider) {
        return new StructuredConceptCatalogProjector(List.of(
                declarationConceptProvider,
                typeUsageConceptProvider,
                entryPointConceptProvider,
                mapperStatementConceptProvider));
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public ConceptDiscoveryApplicationService conceptDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            StructuredConceptCatalogProjector structuredConceptCatalogProjector,
            ConceptSearchDocumentProjector conceptSearchDocumentProjector,
            ConceptSearchMatcher conceptSearchMatcher) {
        return new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                structuredConceptCatalogProjector,
                conceptSearchDocumentProjector,
                conceptSearchMatcher);
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public DiscoveryFollowUpFactory discoveryFollowUpFactory() {
        return new DiscoveryFollowUpFactory();
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, SourceSymbolResolver.class})
    public SourceSymbolResolutionApplicationService sourceSymbolResolutionApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SourceSymbolResolver resolver,
            DiscoveryFollowUpFactory discoveryFollowUpFactory,
            SourceSymbolResolutionProperties properties) {
        return new SourceSymbolResolutionApplicationService(
                repositoryApplicationService, resolver, discoveryFollowUpFactory, properties);
    }

    @Bean
    @ConditionalOnBean({RepositoryApplicationService.class, RevisionBoundRepositorySyntaxProvider.class})
    public TypeMemberDiscoveryApplicationService typeMemberDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            DiscoveryFollowUpFactory discoveryFollowUpFactory) {
        return new TypeMemberDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                discoveryFollowUpFactory);
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            RevisionBoundRepositorySyntaxProvider.class,
            RevisionPinnedSourceRangeReader.class
    })
    public EvidenceSourceApplicationService evidenceSourceApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            RevisionPinnedSourceRangeReader sourceRangeReader) {
        return new EvidenceSourceApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                sourceRangeReader);
    }

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
            RevisionBoundRepositorySyntaxProvider.class,
            JavaSemanticService.class
    })
    public MethodImplementationDiscoveryApplicationService methodImplementationDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            JavaSemanticService semanticService,
            ImplementationDiscoveryProperties properties) {
        return new MethodImplementationDiscoveryApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                new CanonicalMethodDeclarationResolver(),
                semanticService,
                new CanonicalTargetProjection(),
                new ImplementationCandidateFactory(),
                properties.candidateLimit());
    }

    @Bean
    @ConditionalOnBean({
            RepositoryApplicationService.class,
            RevisionBoundRepositorySyntaxProvider.class,
            JavaSemanticService.class
    })
    public SemanticAnalysisApplicationService semanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder outgoingBuilder,
            IncomingSemanticCallGraphBuilder incomingBuilder,
            OutgoingGraphProperties outgoingGraphProperties,
            IncomingGraphProperties incomingGraphProperties) {
        return new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                new CanonicalMethodDeclarationResolver(),
                semanticService,
                outgoingBuilder,
                incomingBuilder,
                outgoingGraphProperties,
                incomingGraphProperties);
    }
}
