package com.java.semantic.semantic.adapter.jdtls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdtProcessTerminatorTest {

    @Test
    void should_force_termination_and_restore_interrupt_status_after_the_first_wait_is_interrupted() {
        InterruptedWaitProcess process = new InterruptedWaitProcess();
        try {
            JdtProcessTerminator.TerminationResult result = JdtProcessTerminator.awaitThenForce(
                    process, Duration.ofMillis(1));

            assertThat(result.terminated()).isTrue();
            assertThat(result.forced()).isTrue();
            assertThat(result.interrupted()).isTrue();
            assertThat(process.isDestroyedForcibly()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class InterruptedWaitProcess extends Process {

        private boolean firstWait = true;
        private boolean destroyedForcibly;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (firstWait) {
                firstWait = false;
                throw new InterruptedException("controlled wait interruption");
            }
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // Graceful process destruction is not used by awaitThenForce.
        }

        @Override
        public Process destroyForcibly() {
            destroyedForcibly = true;
            return this;
        }

        private boolean isDestroyedForcibly() {
            return destroyedForcibly;
        }
    }
}
