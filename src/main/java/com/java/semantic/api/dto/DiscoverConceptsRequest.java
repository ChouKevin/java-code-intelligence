package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.syntax.application.ConceptKind;
import com.java.semantic.syntax.application.ConceptMatchMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 結構化概念探索的封閉 HTTP 請求 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class DiscoverConceptsRequest {

    @NotBlank
    private String repoId;

    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$")
    private String expectedRevision;

    @NotEmpty
    @Size(max = 4)
    @Valid
    private List<Term> terms;

    @NotEmpty
    private List<@NotNull ConceptKind> kinds;

    @NotBlank
    @Pattern(regexp = "ALL")
    private String operator;

    @Size(max = 255)
    private String packagePrefix;

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

    public List<Term> terms() {
        return terms;
    }

    public List<ConceptKind> kinds() {
        return kinds;
    }

    public String operator() {
        return operator;
    }

    public String packagePrefix() {
        return packagePrefix;
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

    @JsonSetter(value = "terms", nulls = Nulls.FAIL)
    public void setTerms(List<Term> terms) {
        this.terms = terms;
    }

    @JsonSetter(value = "kinds", nulls = Nulls.FAIL)
    public void setKinds(List<ConceptKind> kinds) {
        this.kinds = kinds;
    }

    @JsonSetter(value = "operator", nulls = Nulls.FAIL)
    public void setOperator(String operator) {
        this.operator = operator;
    }

    @JsonSetter("packagePrefix")
    public void setPackagePrefix(String packagePrefix) {
        this.packagePrefix = packagePrefix;
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

    /** 單一概念搜尋條件的封閉 HTTP 值 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Term(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank @Size(min = 2, max = 128) String value,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotNull ConceptMatchMode matchMode) {

        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            throw new IllegalArgumentException("unknown term property");
        }
    }
}
