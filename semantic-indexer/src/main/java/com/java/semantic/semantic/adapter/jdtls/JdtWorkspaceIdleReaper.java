package com.java.semantic.semantic.adapter.jdtls;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/** 週期性觸發非阻塞工作區維護；所有所有權判斷仍由 manager 負責。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "semantic.jdtls", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class JdtWorkspaceIdleReaper {

    private final DefaultJdtWorkspaceManager workspaceManager;
    private final JdtWorkspaceLifecycleMetrics lifecycleMetrics;

    public JdtWorkspaceIdleReaper(
            DefaultJdtWorkspaceManager workspaceManager,
            JdtWorkspaceLifecycleMetrics lifecycleMetrics) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager is required");
        this.lifecycleMetrics = Objects.requireNonNull(lifecycleMetrics, "lifecycleMetrics is required");
    }

    @Scheduled(fixedDelayString = "${semantic.jdtls.maintenance-interval:1m}")
    public void runOnce() {
        try {
            MaintenanceReport report = workspaceManager.runMaintenance();
            recordMaintenance(report.skippedBusy()
                    ? JdtWorkspaceLifecycleMetrics.MaintenanceOutcome.SKIPPED_BUSY
                    : JdtWorkspaceLifecycleMetrics.MaintenanceOutcome.COMPLETED);
            log.debug(
                    "phase=jdtls-maintenance outcome=completed skippedBusy={} confirmedRetries={} idleEvictions={} retainedProcesses={}",
                    report.skippedBusy(), report.confirmedRetries(), report.idleEvictions(),
                    report.retainedProcesses());
        } catch (RuntimeException exception) {
            JdtFatalErrorPolicy.rethrowIfFatal(exception);
            recordMaintenance(JdtWorkspaceLifecycleMetrics.MaintenanceOutcome.FAILED);
            log.error("phase=jdtls-maintenance outcome=failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void recordMaintenance(JdtWorkspaceLifecycleMetrics.MaintenanceOutcome outcome) {
        try {
            lifecycleMetrics.recordMaintenance(outcome);
        } catch (RuntimeException exception) {
            log.debug("phase=jdtls-metrics outcome=record-failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
