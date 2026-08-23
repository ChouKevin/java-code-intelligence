package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.semantic.syntax.application.TypeMemberKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 型別成員探索的封閉 HTTP 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class DiscoverTypeMembersRequest {

    @NotBlank
    private String repoId;

    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{40}$")
    private String expectedRevision;

    @NotNull
    @Valid
    private SourceTypeIdentityPayload sourceType;

    @NotEmpty
    private List<@NotNull TypeMemberKind> memberKinds;

    @Size(max = 255)
    private String namePrefix;

    @Min(0)
    private Integer offset = 0;

    @Min(1)
    @Max(100)
    private Integer limit = 50;

    public String repoId() {
        return repoId;
    }

    public String expectedRevision() {
        return expectedRevision;
    }

    public SourceTypeIdentityPayload sourceType() {
        return sourceType;
    }

    public List<TypeMemberKind> memberKinds() {
        return memberKinds;
    }

    public String namePrefix() {
        return namePrefix;
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

    @JsonSetter("expectedRevision")
    public void setExpectedRevision(String expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    @JsonSetter("sourceType")
    public void setSourceType(SourceTypeIdentityPayload sourceType) {
        this.sourceType = sourceType;
    }

    @JsonSetter(value = "memberKinds", nulls = Nulls.FAIL)
    public void setMemberKinds(List<TypeMemberKind> memberKinds) {
        this.memberKinds = memberKinds;
    }

    @JsonSetter("namePrefix")
    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
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
