package com.java.semantic.syntax.application.concept;

import java.util.Objects;

import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;

/** mapper 類概念的唯一識別，供 logical statement 與完整實體證據以穩定值合併目錄 */
public sealed interface MapperConceptIdentity extends ConceptIdentity permits
        MapperConceptIdentity.MapperStatementConceptIdentity,
        MapperConceptIdentity.MapperStatementVariantEvidenceIdentity {

    /** MAPPER_STATEMENT 候選只以 logical namespace 與 statement ID 識別 */
    record MapperStatementConceptIdentity(MapperStatementKey statementKey)
            implements MapperConceptIdentity {

        public MapperStatementConceptIdentity {
            statementKey = Objects.requireNonNull(statementKey, "statementKey is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MAPPER_STATEMENT;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.MAPPER_STATEMENT;
        }
    }

    /** MAPPER_STATEMENT 候選所保留的完整 statement 變體證據識別 */
    record MapperStatementVariantEvidenceIdentity(MapperStatementIdentity mapperStatement)
            implements MapperConceptIdentity {

        public MapperStatementVariantEvidenceIdentity {
            mapperStatement = Objects.requireNonNull(mapperStatement, "mapperStatement is required");
        }

        @Override
        public ConceptKind kind() {
            return ConceptKind.MAPPER_STATEMENT;
        }

        @Override
        public ConceptIdentityKind identityKind() {
            return ConceptIdentityKind.MAPPER_STATEMENT_VARIANT;
        }
    }
}
