package com.java.semantic.semantic.adapter.jdtls;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdtWorkspaceLifecycleMetricsTest {

    @Test
    void should_publish_fixed_aggregate_lifecycle_metrics_without_identity_tags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicReference<JdtWorkspaceLifecycleMetrics.LifecycleSnapshot> snapshot = new AtomicReference<>(
                new JdtWorkspaceLifecycleMetrics.LifecycleSnapshot(
                        Map.of(JdtWorkspaceLifecycleMetrics.WorkspaceState.READY, 1),
                        Map.of(WorkspaceActivityKind.INDEXING, 1)));
        JdtWorkspaceLifecycleMetrics metrics = new JdtWorkspaceLifecycleMetrics(registry);

        metrics.bind(snapshot::get);
        metrics.bind(snapshot::get);
        snapshot.set(new JdtWorkspaceLifecycleMetrics.LifecycleSnapshot(
                Map.of(JdtWorkspaceLifecycleMetrics.WorkspaceState.READY, 2),
                Map.of(WorkspaceActivityKind.INDEXING, 3)));
        metrics.recordEviction(
                JdtWorkspaceLifecycleMetrics.EvictionTrigger.DEMAND,
                JdtWorkspaceLifecycleMetrics.TerminationOutcome.GRACEFUL);
        metrics.recordTermination(
                JdtWorkspaceLifecycleMetrics.EvictionTrigger.IDLE,
                JdtWorkspaceLifecycleMetrics.TerminationOutcome.FORCED);
        metrics.recordCapacityRejection(
                JdtWorkspaceLifecycleMetrics.CapacityRejectionReason.NO_EVICTABLE_WORKSPACE);
        metrics.recordMaintenance(JdtWorkspaceLifecycleMetrics.MaintenanceOutcome.COMPLETED);

        assertThat(registry.get("jdtls.workspaces.tracked").tag("state", "ready").gauge().value())
                .isEqualTo(2.0);
        assertThat(registry.get("jdtls.workspace.activity").tag("kind", "indexing").gauge().value())
                .isEqualTo(3.0);
        assertThat(registry.find("jdtls.workspaces.tracked").gauges()).hasSize(4);
        assertThat(registry.find("jdtls.workspace.activity").gauges()).hasSize(3);
        assertThat(registry.get("jdtls.workspace.evictions")
                .tags("trigger", "demand", "outcome", "graceful").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("jdtls.process.terminations")
                .tags("trigger", "idle", "outcome", "forced").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("jdtls.workspace.capacity.rejections")
                .tag("reason", "no_evictable_workspace").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("jdtls.workspace.maintenance.runs")
                .tag("outcome", "completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("jdtls.workspace.evictions").counters()).hasSize(20);
        assertThat(registry.find("jdtls.process.terminations").counters()).hasSize(20);
        assertThat(registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .toList())
                .doesNotContain("repository", "revision", "pid", "path", "operation", "error", "payload");
    }
}
