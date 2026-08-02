package com.java.semantic.api;

import com.java.semantic.api.dto.identity.MapperFragmentIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 集中擁有 mapper domain identity 與 HTTP payload 的雙向轉換 */
@Component
public final class MapperIdentityHttpMapper {

    /** 將 mapper statement 邏輯 key 投影為共享 HTTP identity */
    public MapperStatementKeyPayload toPayload(MapperStatementKey key) {
        MapperStatementKey statementKey = Objects.requireNonNull(key, "key is required");
        return new MapperStatementKeyPayload(statementKey.namespace(), statementKey.statementId());
    }

    /** 將 mapper statement 實體 identity 投影為共享 HTTP identity */
    public MapperStatementIdentityPayload toPayload(MapperStatementIdentity identity) {
        MapperStatementIdentity statementIdentity = Objects.requireNonNull(identity, "identity is required");
        return new MapperStatementIdentityPayload(
                toPayload(statementIdentity.statementKey()),
                statementIdentity.resourcePath(),
                statementIdentity.databaseId(),
                statementIdentity.documentOrdinal(),
                toPayload(statementIdentity.representation()));
    }

    /** 將 mapper fragment 實體 identity 投影為共享 HTTP identity */
    public MapperFragmentIdentityPayload toPayload(MapperFragmentIdentity identity) {
        MapperFragmentIdentity fragmentIdentity = Objects.requireNonNull(identity, "identity is required");
        return new MapperFragmentIdentityPayload(
                fragmentIdentity.namespace(),
                fragmentIdentity.fragmentId(),
                fragmentIdentity.resourcePath(),
                fragmentIdentity.documentOrdinal(),
                toPayload(fragmentIdentity.representation()));
    }

    /** 將共享 HTTP identity 還原為 mapper statement 邏輯 key */
    public MapperStatementKey toDomain(MapperStatementKeyPayload payload) {
        MapperStatementKeyPayload statementKey = Objects.requireNonNull(payload, "payload is required");
        return new MapperStatementKey(statementKey.namespace(), statementKey.statementId());
    }

    /** 將共享 HTTP identity 還原為 mapper statement 實體 identity */
    public MapperStatementIdentity toDomain(MapperStatementIdentityPayload payload) {
        MapperStatementIdentityPayload statementIdentity = Objects.requireNonNull(payload, "payload is required");
        return new MapperStatementIdentity(
                toDomain(statementIdentity.statementKey()),
                statementIdentity.resourcePath(),
                statementIdentity.databaseId(),
                statementIdentity.documentOrdinal(),
                statementRepresentation(statementIdentity.representation()));
    }

    /** 將共享 HTTP identity 還原為 mapper fragment 實體 identity */
    public MapperFragmentIdentity toDomain(MapperFragmentIdentityPayload payload) {
        MapperFragmentIdentityPayload fragmentIdentity = Objects.requireNonNull(payload, "payload is required");
        return new MapperFragmentIdentity(
                fragmentIdentity.namespace(),
                fragmentIdentity.fragmentId(),
                fragmentIdentity.resourcePath(),
                fragmentIdentity.documentOrdinal(),
                fragmentRepresentation(fragmentIdentity.representation()));
    }

    private String toPayload(MapperEvidenceRepresentation representation) {
        MapperEvidenceRepresentation evidenceRepresentation = Objects.requireNonNull(
                representation, "representation is required");
        return switch (evidenceRepresentation) {
            case MAPPER_XML_ELEMENT -> "MAPPER_XML_ELEMENT";
            case ANNOTATION_SQL_TEXT -> "ANNOTATION_SQL_TEXT";
        };
    }

    private MapperEvidenceRepresentation statementRepresentation(String representation) {
        return switch (Objects.requireNonNull(representation, "representation is required")) {
            case "MAPPER_XML_ELEMENT" -> MapperEvidenceRepresentation.MAPPER_XML_ELEMENT;
            case "ANNOTATION_SQL_TEXT" -> MapperEvidenceRepresentation.ANNOTATION_SQL_TEXT;
            default -> throw new IllegalArgumentException("unsupported mapper statement representation");
        };
    }

    private MapperEvidenceRepresentation fragmentRepresentation(String representation) {
        return switch (Objects.requireNonNull(representation, "representation is required")) {
            case "MAPPER_XML_ELEMENT" -> MapperEvidenceRepresentation.MAPPER_XML_ELEMENT;
            default -> throw new IllegalArgumentException("unsupported mapper fragment representation");
        };
    }
}
