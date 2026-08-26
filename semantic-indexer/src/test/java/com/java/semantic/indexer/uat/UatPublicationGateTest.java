package com.java.semantic.indexer.uat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UatPublicationGateTest {
    @Test
    void arm_blocks_the_current_cycle_until_release_then_allows_a_new_cycle() throws Exception {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(2));

        long firstCycle = gate.arm();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(gate::awaitPublication);

        assertThatIllegalStateException().isThrownBy(gate::arm).withMessageContaining("active");
        assertThat(waiting.isDone()).isFalse();

        gate.release();
        long secondCycle = gate.arm();
        waiting.get(1, TimeUnit.SECONDS);

        assertThat(secondCycle).isGreaterThan(firstCycle);
        gate.release();
    }

    @Test
    void timeout_clears_the_cycle_before_the_next_arm() {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofNanos(1));

        gate.arm();

        assertThatThrownBy(gate::awaitPublication)
                .isInstanceOf(UatPublicationGate.PublicationGateTimeoutException.class);
        assertThat(gate.arm()).isEqualTo(2L);
        gate.release();
    }

    @Test
    void interruption_clears_the_cycle_before_the_next_arm() throws Exception {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gate.arm();
        Thread waiting = new Thread(() -> {
            try {
                gate.awaitPublication();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });

        waiting.start();
        waiting.interrupt();
        waiting.join(1_000L);

        assertThat(waiting.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(UatPublicationGate.PublicationGateInterruptedException.class);
        assertThat(gate.arm()).isEqualTo(2L);
        gate.release();
    }
}
