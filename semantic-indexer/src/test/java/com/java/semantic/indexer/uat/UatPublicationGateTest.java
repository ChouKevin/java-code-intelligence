package com.java.semantic.indexer.uat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void observer_returns_the_armed_cycle_only_after_the_build_reaches_the_gate_without_releasing_it() throws Exception {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(2));

        long armedCycle = gate.arm();
        CompletableFuture<Void> building = CompletableFuture.runAsync(gate::awaitPublication);

        assertThat(gate.awaitReachedPublication()).isEqualTo(armedCycle);
        assertThat(building.isDone()).isFalse();

        gate.release();
        building.get(1, TimeUnit.SECONDS);
    }

    @Test
    void observer_does_not_report_a_cycle_that_is_aborted_before_the_build_reaches_the_gate() {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));

        gate.arm();
        gate.abortPublication();

        assertThatThrownBy(gate::awaitReachedPublication)
                .isInstanceOf(UatPublicationGate.PublicationObservationUnavailableException.class);
    }

    @Test
    void observer_timeout_does_not_release_or_clear_the_armed_cycle() {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofNanos(1));

        gate.arm();

        assertThatThrownBy(gate::awaitReachedPublication)
                .isInstanceOf(UatPublicationGate.PublicationObservationTimeoutException.class);
        assertThatIllegalStateException().isThrownBy(gate::arm).withMessageContaining("active");
        gate.release();
    }

    @Test
    void observer_interruption_preserves_the_interrupt_and_does_not_clear_the_armed_cycle() throws Exception {
        UatPublicationGate gate = new UatPublicationGate(Duration.ofSeconds(1));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        gate.arm();
        Thread observer = new Thread(() -> {
            try {
                gate.awaitReachedPublication();
            } catch (Throwable exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        observer.start();
        observer.interrupt();
        observer.join(1_000L);

        assertThat(observer.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(UatPublicationGate.PublicationObservationInterruptedException.class);
        assertThat(interrupted).isTrue();
        assertThatIllegalStateException().isThrownBy(gate::arm).withMessageContaining("active");
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
