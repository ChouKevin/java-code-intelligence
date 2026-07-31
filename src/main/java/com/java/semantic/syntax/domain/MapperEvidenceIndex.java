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

    private static final Comparator<MapperFragmentIdentity> FRAGMENT_IDENTITY_ORDER =
            Comparator.comparing(MapperFragmentIdentity::namespace)
                    .thenComparing(MapperFragmentIdentity::fragmentId)
                    .thenComparing(MapperFragmentIdentity::resourcePath)
                    .thenComparingInt(MapperFragmentIdentity::documentOrdinal)
                    .thenComparing(MapperFragmentIdentity::representation);

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
        List<MapperStatementEvidence> matches = new ArrayList<>();
        for (MapperStatementEvidence evidence : statementsByIdentity.values()) {
            if (evidence.identity().namespace().equals(namespace)
                    && evidence.identity().statementId().equals(statementId)) {
                matches.add(evidence);
            }
        }
        return List.copyOf(matches);
    }

    /** 保存 extraction 順序的所有 statement 證據 */
    public List<MapperStatementEvidence> statements() {
        return List.copyOf(statementsByIdentity.values());
    }

    /** 保存 extraction 順序的所有 fragment 證據 */
    public List<MapperFragmentEvidence> fragments() {
        return List.copyOf(fragmentsByIdentity.values());
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
                .sorted(FRAGMENT_IDENTITY_ORDER)
                .toList();
    }

    private static boolean matchesInclude(
            MapperStatementIdentity statement,
            String refId,
            MapperFragmentIdentity fragment) {
        if (refId.contains(".")) {
            return (fragment.namespace() + "." + fragment.fragmentId()).equals(refId);
        }
        return fragment.namespace().equals(statement.namespace())
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
                throw new IllegalStateException("duplicate mapper statement identity");
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
                throw new IllegalStateException("duplicate mapper fragment identity");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }
}
