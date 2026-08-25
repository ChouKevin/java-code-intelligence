package com.java.semantic.repository.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void read_status_times_out_while_another_thread_holds_the_runtime_write_lock() throws Exception {
        DefaultRepositoryApplicationService service = service(Duration.ofMillis(10));
        RepositoryRuntime runtime = service.registry().get(RepositoryId.of("orders"));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            runtime.lock().writeLock().lock();
            try {
                lockHeld.countDown();
                try {
                    releaseLock.await();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                runtime.lock().writeLock().unlock();
            }
        });
        try {
            assertThat(lockHeld.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.status(RepositoryId.of("orders")))
                    .isInstanceOf(RepositoryBusyException.class);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releases_the_read_lock_after_a_snapshot_operation_fails() throws Exception {
        DefaultRepositoryApplicationService service = service(Duration.ofMillis(100));
        RepositoryRuntime runtime = service.registry().get(RepositoryId.of("orders"));
        runtime.publish(new RepositoryRevision("a".repeat(40)));

        assertThatThrownBy(() -> service.withSnapshot(RepositoryId.of("orders"), Optional.empty(), snapshot -> {
            throw new IllegalStateException("planned read failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(runtime.lock().writeLock().tryLock(100, TimeUnit.MILLISECONDS)).isTrue();
        runtime.lock().writeLock().unlock();
    }

    @Test
    void runtime_lock_remains_fair_for_read_side_access() {
        DefaultRepositoryApplicationService service = service(Duration.ofMillis(100));

        assertThat(service.registry().get(RepositoryId.of("orders")).lock().isFair()).isTrue();
    }

    private DefaultRepositoryApplicationService service(Duration timeout) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("repos").toString());
        properties.setRepositoryLockTimeout(timeout);
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.test/orders.git");
        properties.getRepositories().put("orders", config);
        return new DefaultRepositoryApplicationService(new RepositoryRuntimeRegistry(properties), properties);
    }
}
