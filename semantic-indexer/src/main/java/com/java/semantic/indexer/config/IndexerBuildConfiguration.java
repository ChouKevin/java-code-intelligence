package com.java.semantic.indexer.config;

import java.time.Duration;
import java.util.Optional;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.build.RepositoryBuildScopeFactory;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.IndexJobProperties;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.uat.NoOpPublicationGate;
import com.java.semantic.indexer.uat.PublicationGate;
import com.java.semantic.indexer.uat.UatPublicationGate;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import com.java.semantic.semantic.domain.JavaSemanticService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Wires job-scoped build assembly and dispatch configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IndexJobProperties.class)
public class IndexerBuildConfiguration {
    @Bean
    public RepositoryBuildRunner.BuildScopeFactory repositoryBuildScopeFactory(ExactRepositoryCheckout checkout,
                                                                                 MongoTemplate template, IndexJobStore jobs,
                                                                                 PublicationPort publication, PublicationGate publicationGate,
                                                                                 JavaSemanticService semanticService,
                                                                                 JdtWorkspaceManager workspaces) {
        return new RepositoryBuildScopeFactory(checkout, template, jobs, publication, publicationGate, semanticService, workspaces);
    }

    @Bean
    public RepositoryBuildRunner repositoryBuildRunner(RepositoryBuildRunner.BuildScopeFactory buildScopeFactory) {
        return new RepositoryBuildRunner(buildScopeFactory);
    }

    @Bean
    public IndexJobExecutor indexJobExecutor(IndexJobStore jobs, RepositoryBuildRunner buildRunner,
                                             PublicationPort publication,
                                             Optional<IndexJobExecutor.ResetJobHandler> resetHandler) {
        return new IndexJobExecutor(jobs, buildRunner, publication, resetHandler);
    }

    @Bean
    @Profile("!uat")
    public PublicationGate noOpPublicationGate() {
        return new NoOpPublicationGate();
    }

    @Bean
    @Profile("uat")
    public UatPublicationGate uatPublicationGate(org.springframework.core.env.Environment environment) {
        Duration timeout = environment.getProperty("semantic.uat.publication-timeout", Duration.class, Duration.ofSeconds(30));
        return new UatPublicationGate(timeout);
    }
}
