package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 監聽器註解的種類與辨識證據 */
public record ListenerAnnotationEvidenceResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String kind, @ApiMonitoringField(ApiMonitoringMode.VALUE) String matchKind) {
}
