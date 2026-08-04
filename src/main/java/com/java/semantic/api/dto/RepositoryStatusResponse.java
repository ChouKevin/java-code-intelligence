package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Optional;

/** 不含路徑、URL、認證與 JGit 型別的儲存庫狀態 */
public record RepositoryStatusResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String mode,
        @MonitoringField(MonitoringMode.SIZE) String displayName,
        @MonitoringField(MonitoringMode.OMIT) Optional<String> currentBranch,
        @MonitoringField(MonitoringMode.OMIT) Optional<String> currentRevision,
        @MonitoringField(MonitoringMode.VALUE) boolean cloned) {
}
