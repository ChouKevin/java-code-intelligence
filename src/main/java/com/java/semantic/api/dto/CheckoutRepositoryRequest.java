package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import jakarta.validation.constraints.NotBlank;

/** checkout 可接受分支、tag 或 commit SHA */
public record CheckoutRepositoryRequest(@ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String revision) {
}
