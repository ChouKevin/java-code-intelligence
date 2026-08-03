package com.java.semantic.mcp.dto.concept;

import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.syntax.application.TypeMemberKind;
import com.java.semantic.syntax.application.TypeMemberResult;
import com.java.semantic.syntax.application.concept.ConceptIdentity;
import com.java.semantic.syntax.application.concept.ConceptKind;
import com.java.semantic.syntax.application.concept.ConceptSearchResult;
import com.java.semantic.syntax.application.concept.ConceptSearchTerm;
import com.java.semantic.syntax.application.concept.RevisionBoundConceptResolution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** concept discovery MCP 查詢的 transport DTO */
public final class ConceptDiscoveryMcpDtos {

    private ConceptDiscoveryMcpDtos() {
        throw new UnsupportedOperationException("utility class");
    }

    /** concept search 輸入 */
    public record DiscoverInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotEmpty @Size(max = 4) List<@NotNull @Valid ConceptSearchTerm> terms,
            @MonitoringField(MonitoringMode.SIZE) @NotEmpty Set<@NotNull ConceptKind> kinds,
            @MonitoringField(MonitoringMode.VALUE) Optional<String> packagePrefix,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer offset,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(100) Integer limit) {
    }

    /** concept search 結果 */
    public record DiscoverOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid ConceptSearchResult result) {
    }

    /** exact concept resolution 輸入 */
    public record ResolveInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid ConceptIdentity identity) {
    }

    /** exact concept resolution 結果 */
    public record ResolveOutput(
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid RevisionBoundConceptResolution result) {
    }

    /** type member discovery 輸入 */
    public record TypeMembersInput(
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{0,63}$") String repoId,
            @MonitoringField(MonitoringMode.VALUE) @NotBlank
            @Pattern(regexp = "^[0-9a-f]{40}$|^FIXTURE$") String expectedRevision,
            @MonitoringField(MonitoringMode.NESTED) @NotNull @Valid SourceTypeIdentity sourceType,
            @MonitoringField(MonitoringMode.SIZE) @NotEmpty Set<@NotNull TypeMemberKind> memberKinds,
            @MonitoringField(MonitoringMode.VALUE) Optional<String> namePrefix,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(0) Integer offset,
            @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(100) Integer limit) {
    }

    /** type member discovery 結果 */
    public record TypeMembersOutput(@MonitoringField(MonitoringMode.NESTED) @NotNull @Valid TypeMemberResult result) {
    }
}
