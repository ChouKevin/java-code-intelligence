package com.java.semantic.model.git;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Aggregate exclusions from the complete direct comparison; contains no excluded identities. */
public record GitComparisonPolicyCoverage(long excludedChanges, List<Exclusion> reasons) {
    public enum Reason { UNSUPPORTED_PATH, SYMLINK, SUBMODULE, OUTSIDE_SOURCE_POLICY }

    public record Exclusion(Reason reason, long count) {
        public Exclusion {
            Objects.requireNonNull(reason, "exclusion reason is required");
            if (count <= 0) throw new IllegalArgumentException("exclusion count must be positive");
        }
    }

    public GitComparisonPolicyCoverage {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "exclusion reasons are required"));
        Set<Reason> unique = EnumSet.noneOf(Reason.class);
        long sum = 0;
        for (Exclusion exclusion : reasons) {
            if (!unique.add(exclusion.reason())) throw new IllegalArgumentException("duplicate exclusion reason");
            sum = Math.addExact(sum, exclusion.count());
        }
        if (excludedChanges < 0 || sum != excludedChanges) throw new IllegalArgumentException("invalid comparison exclusion total");
        reasons = reasons.stream().sorted(java.util.Comparator.comparing(Exclusion::reason)).toList();
    }

    public Map<String, Object> toFields() {
        return Map.of("excludedChanges", excludedChanges, "reasons", reasons.stream()
                .map(exclusion -> Map.<String, Object>of("reason", exclusion.reason().name(), "count", exclusion.count())).toList());
    }

    public static GitComparisonPolicyCoverage fromFields(Map<?, ?> fields) {
        if (Objects.isNull(fields) || !fields.keySet().equals(Set.of("excludedChanges", "reasons"))
                || !(fields.get("excludedChanges") instanceof Long total) || !(fields.get("reasons") instanceof List<?> values)) {
            throw new IllegalArgumentException("required comparison policy coverage is malformed");
        }
        List<Exclusion> reasons = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> reason) || !reason.keySet().equals(Set.of("reason", "count"))
                    || !(reason.get("reason") instanceof String name) || !(reason.get("count") instanceof Long count)) {
                throw new IllegalArgumentException("comparison exclusion reason is malformed");
            }
            reasons.add(new Exclusion(Reason.valueOf(name), count));
        }
        return new GitComparisonPolicyCoverage(total, reasons);
    }
}
