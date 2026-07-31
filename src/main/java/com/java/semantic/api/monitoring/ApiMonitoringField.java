package com.java.semantic.api.monitoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 明確標示 record component 的 API 監控輸出規則 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiMonitoringField {

    ApiMonitoringMode value();
}
