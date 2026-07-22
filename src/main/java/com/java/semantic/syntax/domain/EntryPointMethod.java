package com.java.semantic.syntax.domain;

/** 入口方法，三種入口共通的形狀 */
public sealed interface EntryPointMethod permits ApiEntryPoint, MqEntryPoint, ScheduleEntryPoint {

    /** 方法名稱 */
    String name();

    /** 方法 Javadoc，取不到時為空字串 */
    String description();

    /** 方法宣告的 canonical analysis target 證明 */
    MethodTargetResolution analysisTarget();

    /** 入口類型 */
    EntryPointType type();
}
