package com.java.semantic.syntax.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** repository syntax 快照中所有 mapper statement 與 fragment 的不可變多值證據索引 */
public final class MapperEvidenceIndex {

    private static final Comparator<MapperStatementEvidence> STATEMENT_LOCATION_ORDER =
            Comparator.comparing((MapperStatementEvidence evidence) -> evidence.location().sourceFile())
                    .thenComparingInt(evidence -> evidence.location().range().start().line())
                    .thenComparingInt(evidence -> evidence.location().range().start().character())
                    .thenComparingInt(evidence -> evidence.location().range().end().line())
                    .thenComparingInt(evidence -> evidence.location().range().end().character())
                    .thenComparing(evidence -> evidence.identity().statementKey().namespace())
                    .thenComparing(evidence -> evidence.identity().statementKey().statementId())
                    .thenComparing(evidence -> evidence.identity().representation());

    private static final Comparator<MapperFragmentEvidence> FRAGMENT_LOCATION_ORDER =
            Comparator.comparing((MapperFragmentEvidence evidence) -> evidence.location().sourceFile())
                    .thenComparingInt(evidence -> evidence.location().range().start().line())
                    .thenComparingInt(evidence -> evidence.location().range().start().character())
                    .thenComparingInt(evidence -> evidence.location().range().end().line())
                    .thenComparingInt(evidence -> evidence.location().range().end().character())
                    .thenComparing(evidence -> evidence.identity().namespace())
                    .thenComparing(evidence -> evidence.identity().fragmentId())
                    .thenComparing(evidence -> evidence.identity().representation());

    private final Map<MapperStatementIdentity, MapperStatementEvidence> statementsByIdentity;

    private final Map<MapperFragmentIdentity, MapperFragmentEvidence> fragmentsByIdentity;

    public MapperEvidenceIndex(
            List<MapperStatementEvidence> statementEvidence,
            List<MapperFragmentEvidence> fragmentEvidence) {
        statementsByIdentity = indexStatements(statementEvidence);
        fragmentsByIdentity = indexFragments(fragmentEvidence);
    }

    /** 建立不含 mapper 證據的不可變索引 */
    public static MapperEvidenceIndex empty() {
        return new MapperEvidenceIndex(List.of(), List.of());
    }

    /** 以完整 typed identity 取得單一 statement 變體 */
    public Optional<MapperStatementEvidence> statement(MapperStatementIdentity identity) {
        return Optional.ofNullable(statementsByIdentity.get(Objects.requireNonNull(identity, "identity is required")));
    }

    /** 以完整 typed identity 取得單一 fragment */
    public Optional<MapperFragmentEvidence> fragment(MapperFragmentIdentity identity) {
        return Optional.ofNullable(fragmentsByIdentity.get(Objects.requireNonNull(identity, "identity is required")));
    }

    /** 保留同 namespace 與 statement id 的每個合法變體 */
    public List<MapperStatementEvidence> statements(String namespace, String statementId) {
        MapperStatementKey statementKey = new MapperStatementKey(namespace, statementId);
        List<MapperStatementEvidence> matches = new ArrayList<>();
        for (MapperStatementEvidence evidence : statementsByIdentity.values()) {
            if (evidence.identity().statementKey().equals(statementKey)) {
                matches.add(evidence);
            }
        }
        matches.sort(STATEMENT_LOCATION_ORDER);
        return List.copyOf(matches);
    }

    /** 保存 extraction 順序的所有 statement 證據 */
    public List<MapperStatementEvidence> statements() {
        return statementsByIdentity.values().stream().sorted(STATEMENT_LOCATION_ORDER).toList();
    }

    /** 保存 extraction 順序的所有 fragment 證據 */
    public List<MapperFragmentEvidence> fragments() {
        return fragmentsByIdentity.values().stream().sorted(FRAGMENT_LOCATION_ORDER).toList();
    }

    /**
     * 以 statement typed identity 與 extraction 取得的 refId 解析完整 fragment identities
     * 回傳既有 canonical identity 順序且不從序列化 XML 反向推導
     */
    public List<MapperFragmentIdentity> fragmentIdentitiesForInclude(
            MapperStatementIdentity statementIdentity,
            String refId) {
        MapperStatementIdentity statement = Objects.requireNonNull(
                statementIdentity, "statementIdentity is required");
        String reference = requiredText(refId, "refId");
        return fragmentsByIdentity.keySet().stream()
                .filter(identity -> matchesInclude(statement, reference, identity))
                .distinct()
                .sorted(Comparator.comparing(fragmentsByIdentity::get, FRAGMENT_LOCATION_ORDER))
                .toList();
    }

    private static boolean matchesInclude(
            MapperStatementIdentity statement,
            String refId,
            MapperFragmentIdentity fragment) {
        if (refId.contains(".")) {
            return (fragment.namespace() + "." + fragment.fragmentId()).equals(refId);
        }
        return fragment.namespace().equals(statement.statementKey().namespace())
                && fragment.fragmentId().equals(refId);
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }

    private static Map<MapperStatementIdentity, MapperStatementEvidence> indexStatements(
            List<MapperStatementEvidence> evidence) {
        Map<MapperStatementIdentity, MapperStatementEvidence> indexed = new LinkedHashMap<>();
        for (MapperStatementEvidence value : Objects.requireNonNull(evidence, "statementEvidence is required")) {
            MapperStatementEvidence existing = indexed.putIfAbsent(value.identity(), value);
            if (Objects.nonNull(existing)) {
                throw new IllegalStateException("duplicate mapper statement identity: " + value.identity());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private static Map<MapperFragmentIdentity, MapperFragmentEvidence> indexFragments(
            List<MapperFragmentEvidence> evidence) {
        Map<MapperFragmentIdentity, MapperFragmentEvidence> indexed = new LinkedHashMap<>();
        for (MapperFragmentEvidence value : Objects.requireNonNull(evidence, "fragmentEvidence is required")) {
            MapperFragmentEvidence existing = indexed.putIfAbsent(value.identity(), value);
            if (Objects.nonNull(existing)) {
                throw new IllegalStateException("duplicate mapper fragment identity: " + value.identity());
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }
}
