package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Optional;

/** 不含路徑、URL、認證與 JGit 型別的儲存庫狀態 */
public record RepositoryStatusResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String mode,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String displayName,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> currentBranch,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> currentRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean cloned) {
}
