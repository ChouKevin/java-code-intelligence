package com.java.semantic.indexer.job;

import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class IndexJobStartupRecoveryTest {
    @Test
    void startup_reconciles_commits_then_fails_unreconciled_running_jobs() throws Exception {
        IndexJobStore jobs = mock(IndexJobStore.class);

        ConfiguredRepositoryPublisher publisher = mock(ConfiguredRepositoryPublisher.class);
        new IndexJobStartupRecovery(jobs, publisher).run(new DefaultApplicationArguments());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(publisher, jobs);
        order.verify(publisher).publish();
        order.verify(jobs).reconcileCommittedJobs();
        order.verify(jobs).failUnreconciledRunningJobs();
    }

    @Test
    void polling_starts_only_after_recovery_runner_returns_and_the_application_is_ready() throws Exception {
        LifecycleFixture fixture = new LifecycleFixture();
        lifecycleFixture = fixture;
        ExecutorService launcher = Executors.newSingleThreadExecutor();
        CompletableFuture<ConfigurableApplicationContext> application = CompletableFuture.supplyAsync(
                () -> new SpringApplicationBuilder(LifecycleApplication.class).web(WebApplicationType.NONE).run(), launcher);

        try {
            CompletableFuture.anyOf(fixture.recoveryStarted, application).get(30L, TimeUnit.SECONDS);
            assertThat(fixture.recoveryStarted.isDone()).isTrue();
            assertThat(fixture.pollStarted.await(150L, TimeUnit.MILLISECONDS)).isFalse();

            fixture.allowRecovery.countDown();
            try (ConfigurableApplicationContext context = application.get(30L, TimeUnit.SECONDS)) {
                assertThat(fixture.pollStarted.await(2L, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            fixture.allowRecovery.countDown();
            try {
                application.get(30L, TimeUnit.SECONDS).close();
            } finally {
                launcher.shutdownNow();
            }
        }
    }

    @SpringBootConfiguration
    static class LifecycleApplication {
        @Bean
        IndexJobStore indexJobStore() {
            return lifecycleFixture.jobs;
        }

        @Bean
        IndexJobExecutor indexJobExecutor() {
            return mock(IndexJobExecutor.class);
        }

        @Bean
        IndexJobProperties indexJobProperties() {
            return new IndexJobProperties(Duration.ofSeconds(1L));
        }

        @Bean
        IndexJobStartupRecovery indexJobStartupRecovery(IndexJobStore jobs) {
            return new IndexJobStartupRecovery(jobs, lifecycleFixture.publisher);
        }

        @Bean
        IndexJobDispatcher indexJobDispatcher(IndexJobStore jobs, IndexJobExecutor executor, IndexJobProperties properties) {
            return new IndexJobDispatcher(jobs, executor, properties);
        }
    }

    private static LifecycleFixture lifecycleFixture;

    private static final class LifecycleFixture {
        private final IndexJobStore jobs = mock(IndexJobStore.class);
        private final ConfiguredRepositoryPublisher publisher = mock(ConfiguredRepositoryPublisher.class);
        private final CompletableFuture<Void> recoveryStarted = new CompletableFuture<>();
        private final CountDownLatch allowRecovery = new CountDownLatch(1);
        private final CountDownLatch pollStarted = new CountDownLatch(1);

        private LifecycleFixture() {
            doAnswer(invocation -> {
                recoveryStarted.complete(null);
                try {
                    allowRecovery.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(publisher).publish();
            when(jobs.startNextAccepted()).thenAnswer(invocation -> {
                pollStarted.countDown();
                return Optional.empty();
            });
        }
    }
}
