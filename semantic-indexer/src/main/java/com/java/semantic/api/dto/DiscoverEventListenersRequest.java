package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.semantic.syntax.application.EventListenerDiscoveryConstraints;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 事件監聽器探索的封閉 HTTP 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class DiscoverEventListenersRequest {

    @NotBlank
    private String repoId;

    @NotBlank
    @Size(max = 1024)
    @Pattern(regexp = "^(?:[\\p{L}_][\\p{L}\\p{N}_]*\\.)+[\\p{L}_][\\p{L}\\p{N}_]*(?:\\[\\])*$")
    private String eventType;

    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$")
    private String expectedRevision;

    @Min(0)
    private Integer offset = 0;

    @Min(EventListenerDiscoveryConstraints.MIN_LIMIT)
    @Max(EventListenerDiscoveryConstraints.MAX_LIMIT)
    private Integer limit = EventListenerDiscoveryConstraints.DEFAULT_LIMIT;

    public String repoId() {
        return repoId;
    }

    public String eventType() {
        return eventType;
    }

    public String expectedRevision() {
        return expectedRevision;
    }

    public Integer offset() {
        return offset;
    }

    public Integer limit() {
        return limit;
    }

    @JsonSetter("repoId")
    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    @JsonSetter("eventType")
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    @JsonSetter("expectedRevision")
    public void setExpectedRevision(String expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    @JsonSetter(value = "offset", nulls = Nulls.FAIL)
    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    @JsonSetter(value = "limit", nulls = Nulls.FAIL)
    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown request property");
    }
}
