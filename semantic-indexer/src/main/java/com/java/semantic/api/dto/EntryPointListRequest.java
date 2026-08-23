package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record EntryPointListRequest(@MonitoringField(MonitoringMode.SIZE) Set<EntryPointType> types) {

    public EntryPointListRequest {
        types = Set.copyOf(types);
    }

    public static EntryPointListRequest from(String rawTypes) {
        if (Objects.isNull(rawTypes)) {
            return new EntryPointListRequest(EnumSet.allOf(EntryPointType.class));
        }
        if (!StringUtils.hasText(rawTypes)) {
            throw new IllegalArgumentException("types must not be blank");
        }
        EnumSet<EntryPointType> parsed = EnumSet.noneOf(EntryPointType.class);
        for (String value : rawTypes.split(",", -1)) {
            if (!StringUtils.hasText(value)) {
                throw new IllegalArgumentException("types contains a blank value");
            }
            if (!Objects.equals(value, value.trim())) {
                throw new IllegalArgumentException("types must not contain surrounding whitespace");
            }
            parsed.add(EntryPointType.valueOf(value));
        }
        return new EntryPointListRequest(parsed);
    }
}
