package com.java.semantic.indexer.config;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.build.RepositoryBuildScopeFactory;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Wires only the job-scoped build assembly; the dispatcher remains a later concern. */
@Configuration(proxyBeanMethods = false)
public class IndexerBuildConfiguration {
    @Bean
    public RepositoryBuildRunner.BuildScopeFactory repositoryBuildScopeFactory(ExactRepositoryCheckout checkout,
                                                                                 MongoTemplate template, IndexJobStore jobs,
                                                                                 PublicationPort publication,
                                                                                 JavaSemanticService semanticService,
                                                                                 JdtWorkspaceManager workspaces) {
        return new RepositoryBuildScopeFactory(checkout, template, jobs, publication, semanticService, workspaces);
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
}
