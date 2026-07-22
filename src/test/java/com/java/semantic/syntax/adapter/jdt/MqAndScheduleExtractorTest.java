package com.java.semantic.syntax.adapter.jdt;

import java.util.List;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.MqBroker;
import com.java.semantic.syntax.domain.MqEntryPoint;
import com.java.semantic.syntax.domain.ScheduleEntryPoint;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MQ 與排程抽取規則，範圍對齊 call graph 分類器既有的 annotation 集合 */
class MqAndScheduleExtractorTest {

    private final List<EntryPointClass> classes = SyntaxFixtures.extractSyntaxFixture().entryPoints();

    @Test
    void should_expose_the_resolved_canonical_target_for_a_mq_entry_point() {
        MethodTarget target = mqOf("rabbitMulti").analysisTarget().target().orElseThrow();

        assertThat(target).isEqualTo(new MethodTarget(
                "src/main/java/com/example/syntax/OrderListeners.java",
                "com.example.syntax",
                "OrderListeners",
                "rabbitMulti",
                List.of("java.lang.String")));
    }

    @Test
    void should_expose_the_resolved_canonical_target_for_a_schedule_entry_point() {
        MethodTarget target = scheduleOf("bothTriggers").analysisTarget().target().orElseThrow();

        assertThat(target).isEqualTo(new MethodTarget(
                "src/main/java/com/example/syntax/OrderJobs.java",
                "com.example.syntax",
                "OrderJobs",
                "bothTriggers",
                List.of()));
    }

    @Test
    void should_prefer_queues_over_value_when_a_rabbit_listener_declares_both() {
        MqEntryPoint entry = mqOf("rabbitMulti");

        assertThat(entry.broker()).isEqualTo(MqBroker.RABBIT);
        assertThat(entry.destinations())
                .as("優先序由呼叫端宣告，不是原始碼書寫順序；舊分析器會取先寫的 value")
                .containsExactly("q1", "q2");
    }

    @Test
    void should_resolve_a_queue_name_when_it_is_written_as_a_cross_file_constant() {
        assertThat(mqOf("rabbitConstant").destinations()).containsExactly("const-queue");
    }

    @Test
    void should_scan_kafka_listeners_when_a_method_declares_topics() {
        MqEntryPoint entry = mqOf("kafka");

        assertThat(entry.broker()).isEqualTo(MqBroker.KAFKA);
        assertThat(entry.destinations())
                .as("Kafka 的目的地寫在 topics，不是 queues")
                .containsExactly("orders", "refunds");
    }

    @Test
    void should_attribute_a_nested_listener_to_its_own_class_when_the_outer_class_also_has_listeners() {
        assertThat(destinationsOf("OrderListeners"))
                .as("舊分析器以遞迴走訪，巢狀方法會被外層重複計算")
                .doesNotContain("inner-queue");
        assertThat(destinationsOf("OrderListeners.InnerListener")).containsExactly("inner-queue");
    }

    @Test
    void should_prefer_cron_over_fixed_delay_when_a_schedule_declares_both() {
        ScheduleEntryPoint entry = scheduleOf("bothTriggers");

        assertThat(entry.triggerKind()).isEqualTo(ScheduleTriggerKind.CRON);
        assertThat(entry.triggerValue())
                .as("舊分析器會取先寫的 fixedDelayString，把 5000 塞進名為 cronExpression 的欄位")
                .isEqualTo("0 0 1 * * *");
    }

    @Test
    void should_read_a_numeric_fixed_delay_when_the_string_variant_is_absent() {
        ScheduleEntryPoint entry = scheduleOf("numericFixedDelay");

        assertThat(entry.triggerKind()).isEqualTo(ScheduleTriggerKind.FIXED_DELAY);
        assertThat(entry.triggerValue()).isEqualTo("5000");
    }

    @Test
    void should_read_a_numeric_fixed_rate_when_the_string_variant_is_absent() {
        ScheduleEntryPoint entry = scheduleOf("numericFixedRate");

        assertThat(entry.triggerKind()).isEqualTo(ScheduleTriggerKind.FIXED_RATE);
        assertThat(entry.triggerValue()).isEqualTo("30000");
    }

    @Test
    void should_resolve_a_cron_expression_when_it_is_written_as_a_cross_file_constant() {
        assertThat(scheduleOf("constantCron").triggerValue()).isEqualTo("0 0 * * * *");
    }

    @Test
    void should_scan_xxl_job_handlers_when_a_method_declares_xxl_job() {
        ScheduleEntryPoint entry = scheduleOf("settle");

        assertThat(entry.triggerKind()).isEqualTo(ScheduleTriggerKind.JOB_HANDLER);
        assertThat(entry.triggerValue()).isEqualTo("settleHandler");
    }

    private List<String> destinationsOf(String className) {
        return classes.stream()
                .filter(entry -> className.equals(entry.className()))
                .flatMap(entry -> entry.methods().stream())
                .filter(MqEntryPoint.class::isInstance)
                .map(MqEntryPoint.class::cast)
                .flatMap(entry -> entry.destinations().stream())
                .toList();
    }

    private MqEntryPoint mqOf(String methodName) {
        return classes.stream()
                .flatMap(entry -> entry.methods().stream())
                .filter(MqEntryPoint.class::isInstance)
                .map(MqEntryPoint.class::cast)
                .filter(entry -> methodName.equals(entry.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no MQ entry point " + methodName));
    }

    private ScheduleEntryPoint scheduleOf(String methodName) {
        return classes.stream()
                .flatMap(entry -> entry.methods().stream())
                .filter(ScheduleEntryPoint.class::isInstance)
                .map(ScheduleEntryPoint.class::cast)
                .filter(entry -> methodName.equals(entry.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no schedule entry point " + methodName));
    }
}
