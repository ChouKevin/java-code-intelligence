package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import jakarta.validation.constraints.NotBlank;

/** checkout 可接受分支、tag 或 commit SHA */
public record CheckoutRepositoryRequest(@MonitoringField(MonitoringMode.VALUE) @NotBlank String revision) {
}
