package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 同步儲存庫的可選分支 */
public record SyncRepositoryRequest(@ApiMonitoringField(ApiMonitoringMode.VALUE) String branch) {
}
