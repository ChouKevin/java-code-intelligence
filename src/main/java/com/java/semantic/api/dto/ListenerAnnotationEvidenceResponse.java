package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 監聽器註解的種類與辨識證據 */
public record ListenerAnnotationEvidenceResponse(@MonitoringField(MonitoringMode.VALUE) String kind, @MonitoringField(MonitoringMode.VALUE) String matchKind) {
}
