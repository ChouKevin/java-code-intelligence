package com.java.semantic.semantic.adapter.jdtls;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Fixed-dimension operational evidence for JDT workspace ownership and termination. */
@Component
public final class JdtWorkspaceLifecycleMetrics {

    private static final String WORKSPACES_TRACKED = "jdtls.workspaces.tracked";
    private static final String WORKSPACE_ACTIVITY = "jdtls.workspace.activity";
    private static final String WORKSPACE_EVICTIONS = "jdtls.workspace.evictions";
    private static final String PROCESS_TERMINATIONS = "jdtls.process.terminations";
    private static final String CAPACITY_REJECTIONS = "jdtls.workspace.capacity.rejections";
    private static final String MAINTENANCE_RUNS = "jdtls.workspace.maintenance.runs";

    private final MeterRegistry meterRegistry;
    private final AtomicBoolean bound = new AtomicBoolean();
    private final Map<EvictionTrigger, Map<TerminationOutcome, Counter>> evictions =
            new EnumMap<>(EvictionTrigger.class);
    private final Map<EvictionTrigger, Map<TerminationOutcome, Counter>> terminations =
            new EnumMap<>(EvictionTrigger.class);
    private final Map<CapacityRejectionReason, Counter> capacityRejections =
            new EnumMap<>(CapacityRejectionReason.class);
    private final Map<MaintenanceOutcome, Counter> maintenanceRuns =
            new EnumMap<>(MaintenanceOutcome.class);
    private volatile Supplier<LifecycleSnapshot> snapshotSupplier = LifecycleSnapshot::empty;

    public JdtWorkspaceLifecycleMetrics(MeterRegistry meterRegistry) {
        MeterRegistry registry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
        this.meterRegistry = registry;
        registerOutcomeCounters(registry, WORKSPACE_EVICTIONS, evictions);
        registerOutcomeCounters(registry, PROCESS_TERMINATIONS, terminations);
        for (CapacityRejectionReason reason : CapacityRejectionReason.values()) {
            capacityRejections.put(reason, Counter.builder(CAPACITY_REJECTIONS)
                    .tag("reason", tag(reason))
                    .register(registry));
        }
        for (MaintenanceOutcome outcome : MaintenanceOutcome.values()) {
            maintenanceRuns.put(outcome, Counter.builder(MAINTENANCE_RUNS)
                    .tag("outcome", tag(outcome))
                    .register(registry));
        }
    }

    void bind(Supplier<LifecycleSnapshot> snapshotSupplier) {
        Supplier<LifecycleSnapshot> supplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier is required");
        if (!bound.compareAndSet(false, true)) {
            return;
        }
        this.snapshotSupplier = supplier;
        for (WorkspaceState state : WorkspaceState.values()) {
            Gauge.builder(WORKSPACES_TRACKED, supplier,
                            source -> source.get().workspaces().getOrDefault(state, 0))
                    .tag("state", tag(state))
                    .register(meterRegistry);
        }
        for (WorkspaceActivityKind kind : WorkspaceActivityKind.values()) {
            Gauge.builder(WORKSPACE_ACTIVITY, supplier,
                            source -> source.get().activities().getOrDefault(kind, 0))
                    .tag("kind", tag(kind))
                    .register(meterRegistry);
        }
    }

    void recordEviction(EvictionTrigger trigger, TerminationOutcome outcome) {
        counter(evictions, trigger, outcome).increment();
    }

    void recordTermination(EvictionTrigger trigger, TerminationOutcome outcome) {
        counter(terminations, trigger, outcome).increment();
    }

    void recordCapacityRejection(CapacityRejectionReason reason) {
        capacityRejections.get(Objects.requireNonNull(reason, "reason is required")).increment();
    }

    void recordMaintenance(MaintenanceOutcome outcome) {
        maintenanceRuns.get(Objects.requireNonNull(outcome, "outcome is required")).increment();
    }

    private void registerOutcomeCounters(
            MeterRegistry registry,
            String metricName,
            Map<EvictionTrigger, Map<TerminationOutcome, Counter>> counters) {
        for (EvictionTrigger trigger : EvictionTrigger.values()) {
            Map<TerminationOutcome, Counter> outcomes = new EnumMap<>(TerminationOutcome.class);
            for (TerminationOutcome outcome : TerminationOutcome.values()) {
                outcomes.put(outcome, Counter.builder(metricName)
                        .tags("trigger", tag(trigger), "outcome", tag(outcome))
                        .register(registry));
            }
            counters.put(trigger, Map.copyOf(outcomes));
        }
    }

    private Counter counter(
            Map<EvictionTrigger, Map<TerminationOutcome, Counter>> counters,
            EvictionTrigger trigger,
            TerminationOutcome outcome) {
        EvictionTrigger requiredTrigger = Objects.requireNonNull(trigger, "trigger is required");
        TerminationOutcome requiredOutcome = Objects.requireNonNull(outcome, "outcome is required");
        return counters.get(requiredTrigger).get(requiredOutcome);
    }

    private String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    enum WorkspaceState {
        STARTING,
        READY,
        FAILED,
        CLOSING
    }

    enum EvictionTrigger {
        DEMAND,
        IDLE,
        INVALIDATION,
        SHUTDOWN,
        MAINTENANCE_RETRY
    }

    enum TerminationOutcome {
        GRACEFUL,
        FORCED,
        UNCONFIRMED,
        SKIPPED_BUSY
    }

    enum MaintenanceOutcome {
        COMPLETED,
        SKIPPED_BUSY,
        FAILED
    }

    enum CapacityRejectionReason {
        NO_EVICTABLE_WORKSPACE
    }

    record LifecycleSnapshot(
            Map<WorkspaceState, Integer> workspaces,
            Map<WorkspaceActivityKind, Integer> activities) {

        LifecycleSnapshot {
            workspaces = Map.copyOf(Objects.requireNonNull(workspaces, "workspaces is required"));
            activities = Map.copyOf(Objects.requireNonNull(activities, "activities is required"));
        }

        static LifecycleSnapshot empty() {
            return new LifecycleSnapshot(Map.of(), Map.of());
        }
    }
}
