package com.java.semantic.indexer.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IndexJobStartupRecoveryConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StartupRecoveryConfiguration.class)
            .withBean(IndexJobWorker.class, () -> mock(IndexJobWorker.class))
            .withBean(IndexJobStore.class, () -> mock(IndexJobStore.class));

    @Test
    void should_enable_startup_recovery_when_explicitly_configured() {
        contextRunner
                .withPropertyValues("semantic.index-jobs.startup-recovery.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(IndexJobStartupRecovery.class));
    }

    @Test
    void should_disable_startup_recovery_when_explicitly_configured() {
        contextRunner
                .withPropertyValues("semantic.index-jobs.startup-recovery.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IndexJobStartupRecovery.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(IndexJobStartupRecovery.class)
    static class StartupRecoveryConfiguration {
    }
}
