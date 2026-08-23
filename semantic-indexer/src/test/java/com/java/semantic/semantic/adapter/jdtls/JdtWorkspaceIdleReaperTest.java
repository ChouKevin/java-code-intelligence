package com.java.semantic.semantic.adapter.jdtls;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdtWorkspaceIdleReaperTest {

    @Test
    void should_recover_from_a_sanitized_maintenance_failure_on_the_next_scheduled_run() {
        String sentinel = "RESTRICTED_MAINTENANCE_SENTINEL_/protected/Secret.java class Secret {}";
        DefaultJdtWorkspaceManager manager = mock(DefaultJdtWorkspaceManager.class);
        when(manager.runMaintenance())
                .thenThrow(new IllegalStateException(sentinel))
                .thenReturn(MaintenanceReport.empty())
                .thenReturn(MaintenanceReport.busy());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics metrics = new JdtWorkspaceLifecycleMetrics(meterRegistry);
        JdtWorkspaceIdleReaper reaper = new JdtWorkspaceIdleReaper(manager, metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(JdtWorkspaceIdleReaper.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            reaper.runOnce();
            reaper.runOnce();
            reaper.runOnce();

            List<ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("outcome=failed"))
                    .toList();
            assertThat(failures).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains(
                        "phase=jdtls-maintenance", "exceptionType=IllegalStateException")
                        .doesNotContain(sentinel, "/protected/", "Secret.java", "class Secret");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(
                    "phase=jdtls-maintenance", "outcome=completed"));
            assertThat(meterRegistry.get("jdtls.workspace.maintenance.runs")
                    .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
            assertThat(meterRegistry.get("jdtls.workspace.maintenance.runs")
                    .tag("outcome", "completed").counter().count()).isEqualTo(1.0);
            assertThat(meterRegistry.get("jdtls.workspace.maintenance.runs")
                    .tag("outcome", "skipped_busy").counter().count()).isEqualTo(1.0);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
