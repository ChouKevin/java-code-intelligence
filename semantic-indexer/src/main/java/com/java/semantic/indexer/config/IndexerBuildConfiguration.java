package com.java.semantic.indexer.config;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.analysis.AnalysisReuseVerifier;
import com.java.semantic.indexer.analysis.ConservativeAnalysisReuseVerifier;
import com.java.semantic.indexer.analysis.DefaultRepositoryAnalysisPreparation;
import com.java.semantic.indexer.analysis.RepositoryAnalysisPreparation;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.indexer.build.ImportedSourcePolicy;
import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.build.RepositoryBuildScopeFactory;
import com.java.semantic.indexer.job.GitEvidenceJobHandler;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.IndexJobProperties;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.indexer.review.ReviewPreparationService;
import com.java.semantic.indexer.review.DefaultReviewEndpointPreparation;
import com.java.semantic.indexer.review.ReviewEndpointPreparationPort;
import com.java.semantic.indexer.review.ReviewGitEvidencePort;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.uat.NoOpPublicationGate;
import com.java.semantic.indexer.uat.PublicationGate;
import com.java.semantic.indexer.uat.UatPublicationGate;
import com.java.semantic.semantic.adapter.jdtls.JdtLsEffectiveEnvironmentInspector;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Wires job-scoped build assembly and dispatch configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IndexJobProperties.class)
public class IndexerBuildConfiguration {
    @Bean
    public RepositoryAnalysisPreparation repositoryAnalysisPreparation(
            JdtWorkspaceManager workspaces, JdtLsEffectiveEnvironmentInspector inspector) {
        return new DefaultRepositoryAnalysisPreparation(workspaces, inspector, new FullIndexPlanner());
    }

    @Bean
    public JdtLsEffectiveEnvironmentInspector jdtLsEffectiveEnvironmentInspector(JdtLsProperties properties) {
        return new JdtLsEffectiveEnvironmentInspector(properties);
    }

    @Bean
    public AnalysisReuseVerifier analysisReuseVerifier(
            RepositoryAnalysisPreparation preparation, GitEvidencePublicationStore evidence,
            RepositoryRuntimeRegistry repositories) {
        return new ConservativeAnalysisReuseVerifier((candidate, target) -> {
            try (PreparedAnalysis analysis = preparation.prepare(target)) {
                return candidate.fingerprint().equals(analysis.fingerprint())
                        && evidence.preparedSource(candidate).policy().equals(ImportedSourcePolicy.from(analysis.plan(),
                                repositories.get(target.snapshot().repositoryId()).projectGuidePath()));
            }
        });
    }

    @Bean
    public RepositoryBuildRunner.BuildScopeFactory repositoryBuildScopeFactory(ExactRepositoryCheckout checkout,
                                                                                 MongoTemplate template, IndexJobStore jobs,
                                                                                 PublicationPort publication, PublicationGate publicationGate,
                                                                                 JdtWorkspaceManager workspaces,
                                                                                 RepositoryAnalysisPreparation analysisPreparation,
                                                                                 JdtLsProperties jdtLsProperties,
                                                                                 GitRepositoryPort git,
                                                                                 GitEvidencePublicationStore evidence,
                                                                                 RepositoryProperties properties,
                                                                                 RepositoryRuntimeRegistry repositories) {
        return new RepositoryBuildScopeFactory(checkout, template, jobs, publication, publicationGate, workspaces,
                analysisPreparation, jdtLsProperties, git, evidence, properties, repositories);
    }

    @Bean
    public RepositoryBuildRunner repositoryBuildRunner(RepositoryBuildRunner.BuildScopeFactory buildScopeFactory) {
        return new RepositoryBuildRunner(buildScopeFactory);
    }

    @Bean
    public ReviewEndpointPreparationPort reviewEndpointPreparationPort(ExactRepositoryCheckout checkout,
                                                                         RepositoryBuildRunner buildRunner,
                                                                         AnalysisReuseVerifier reuseVerifier) {
        return new DefaultReviewEndpointPreparation(checkout, buildRunner, reuseVerifier);
    }

    @Bean
    public ReviewGitEvidencePort reviewGitEvidencePort(GitEvidenceJobHandler gitEvidence) {
        return new ReviewGitEvidencePort() {
            @Override
            public com.java.semantic.model.review.ResolvedReviewEndpoints resolve(IndexJob reviewJob) {
                return gitEvidence.resolveReview(reviewJob);
            }

            @Override
            public void prepare(IndexJob reviewJob) {
                gitEvidence.prepareReview(reviewJob);
            }
        };
    }

    @Bean
    public IndexJobExecutor indexJobExecutor(IndexJobStore jobs, RepositoryBuildRunner buildRunner,
                                             PublicationPort publication,
                                             Optional<IndexJobExecutor.ResetJobHandler> resetHandler,
                                             Optional<GitEvidenceJobHandler> gitEvidenceHandler,
                                             ReviewPreparationService reviewPreparationService) {
        return new IndexJobExecutor(jobs, buildRunner, publication, resetHandler, gitEvidenceHandler,
                Optional.of(reviewPreparationService));
    }

    @Bean
    @Profile("!uat")
    public PublicationGate noOpPublicationGate() {
        return new NoOpPublicationGate();
    }

    @Bean
    @Profile("uat")
    public UatPublicationGate uatPublicationGate(Environment environment) {
        Duration timeout = environment.getProperty("semantic.uat.publication-timeout", Duration.class, Duration.ofSeconds(30));
        return new UatPublicationGate(timeout);
    }
}
