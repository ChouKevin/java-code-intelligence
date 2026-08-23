package com.java.semantic.monitoring;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 將標註監控規則的 record 投影為中立監控欄位 */
public final class MonitoringProjection {

    private MonitoringProjection() {
    }

    public static boolean hasMonitoringFields(Object value) {
        Object recordValue = Objects.requireNonNull(value, "value is required");
        if (!recordValue.getClass().isRecord()) {
            return false;
        }
        for (RecordComponent component : recordValue.getClass().getRecordComponents()) {
            if (Objects.nonNull(component.getAnnotation(MonitoringField.class))) {
                return true;
            }
        }
        return false;
    }

    public static List<MonitoringComponent> project(Object value) {
        Object recordValue = Objects.requireNonNull(value, "value is required");
        if (!recordValue.getClass().isRecord()) {
            throw new IllegalArgumentException("monitoring projection accepts only records");
        }
        List<MonitoringComponent> components = new ArrayList<>();
        for (RecordComponent component : recordValue.getClass().getRecordComponents()) {
            MonitoringField field = component.getAnnotation(MonitoringField.class);
            if (Objects.isNull(field)) {
                throw new IllegalStateException("monitoring contract defect: every projected record component requires MonitoringField");
            }
            components.add(new MonitoringComponent(
                    component.getName(), field.value(), componentValue(recordValue, component)));
        }
        return List.copyOf(components);
    }

    /** 保存單一 record component 的中立監控投影 */
    public record MonitoringComponent(String name, MonitoringMode mode, Object value) {

        public MonitoringComponent {
            name = Objects.requireNonNull(name, "name is required");
            mode = Objects.requireNonNull(mode, "mode is required");
        }
    }

    private static Object componentValue(Object record, RecordComponent component) {
        try {
            Method accessor = component.getAccessor();
            if (!accessor.trySetAccessible()) {
                throw new IllegalStateException("monitoring projection cannot access a record component");
            }
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("monitoring projection cannot read a record component", exception);
        }
    }
}
