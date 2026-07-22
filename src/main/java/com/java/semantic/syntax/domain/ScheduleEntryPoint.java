package com.java.semantic.syntax.domain;

import java.util.Objects;

/**
 * 排程任務
 *
 * @param name         方法名稱
 * @param description  方法 Javadoc
 * @param triggerKind  觸發方式，決定 triggerValue 該怎麼讀
 * @param triggerValue 觸發設定值，讀不出來時為空字串
 */
public record ScheduleEntryPoint(
        String name,
        String description,
        ScheduleTriggerKind triggerKind,
        String triggerValue,
        MethodTargetResolution analysisTarget) implements EntryPointMethod {

    public ScheduleEntryPoint {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }

    @Override
    public EntryPointType type() {
        return EntryPointType.SCHEDULE;
    }
}
