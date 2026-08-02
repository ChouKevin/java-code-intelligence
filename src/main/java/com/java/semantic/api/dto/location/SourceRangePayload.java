package com.java.semantic.api.dto.location;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.Optional;

/** 可獨立導覽且含儲存庫相對來源檔案的零基 UTF-16 半開區間 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SourceRangePayload(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) @NotNull @Valid TextRangePayload range) {

    public SourceRangePayload {
        sourceFile = Optional.ofNullable(sourceFile)
                .orElseThrow(() -> new IllegalArgumentException("sourceFile is required"));
        if (sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile must not be blank");
        }
        range = Objects.requireNonNull(range, "range is required");
    }
}
