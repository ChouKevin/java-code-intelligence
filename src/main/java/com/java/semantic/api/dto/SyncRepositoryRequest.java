package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 同步儲存庫的可選分支 */
public record SyncRepositoryRequest(@MonitoringField(MonitoringMode.VALUE) String branch) {
}
