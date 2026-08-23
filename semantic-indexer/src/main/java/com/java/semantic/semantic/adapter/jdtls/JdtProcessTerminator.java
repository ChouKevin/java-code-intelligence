package com.java.semantic.semantic.adapter.jdtls;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 對 JDT LS 子程序執行有界、可確認的終止流程。 */
final class JdtProcessTerminator {

    private static final String NO_FAILURE = "NONE";

    private JdtProcessTerminator() {
        throw new AssertionError("JdtProcessTerminator must not be instantiated");
    }

    static TerminationResult awaitThenForce(Process process, Duration timeout) {
        Objects.requireNonNull(process, "process is required");
        Objects.requireNonNull(timeout, "timeout is required");
        WaitResult gracefulWait = waitFor(process, timeout);
        boolean interrupted = gracefulWait.interrupted();
        if (gracefulWait.terminated()) {
            restoreInterrupt(interrupted);
            return new TerminationResult(true, false, interrupted, NO_FAILURE);
        }

        String failureType = gracefulWait.failureType();
        try {
            process.destroyForcibly();
        } catch (RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
            failureType = exception.getClass().getSimpleName();
        }

        WaitResult forcedWait = waitFor(process, timeout);
        interrupted |= forcedWait.interrupted();
        if (!NO_FAILURE.equals(forcedWait.failureType())) {
            failureType = forcedWait.failureType();
        }
        boolean terminated = forcedWait.terminated() || !process.isAlive();
        restoreInterrupt(interrupted);
        return new TerminationResult(terminated, true, interrupted, failureType);
    }

    static TerminationResult destroyThenAwait(Process process, Duration timeout) {
        Objects.requireNonNull(process, "process is required");
        try {
            process.destroy();
        } catch (RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
        }
        return awaitThenForce(process, timeout);
    }

    private static WaitResult waitFor(Process process, Duration timeout) {
        try {
            boolean terminated = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new WaitResult(terminated, false, NO_FAILURE);
        } catch (InterruptedException exception) {
            return new WaitResult(false, true, exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
            return new WaitResult(false, false, exception.getClass().getSimpleName());
        }
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    record TerminationResult(
            boolean terminated,
            boolean forced,
            boolean interrupted,
            String failureType) {
    }

    private record WaitResult(boolean terminated, boolean interrupted, String failureType) {
    }
}
