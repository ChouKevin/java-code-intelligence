package com.java.semantic.query;

import com.java.semantic.model.index.SemanticAnalysisEvidence;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies Query's production converter reads the schema-v3/v1 semantic-limitation BSON contract. */
class SemanticAnalysisEvidenceMongoCompatibilityTest {

    @Test
    void query_converter_reads_absent_null_and_source_scoped_v1_limitations() {
        Document persisted = new Document("contractVersion", 1).append("fingerprintDigest", "a".repeat(64))
                .append("buildStatus", "SUCCESS").append("projects", List.of())
                .append("resolution", new Document("attempted", 0L).append("resolved", 0L).append("unresolved", 0L)
                        .append("ambiguous", 0L).append("external", 0L))
                .append("limitations", List.of(new Document("code", "ABSENT_GLOBAL"), new Document("code", "NULL_GLOBAL")
                        .append("sourcePath", null), new Document("code", "SOURCE_SCOPED").append("sourcePath", "src/Order.java")));

        SemanticAnalysisEvidence decoded = queryConverter().read(SemanticAnalysisEvidence.class, persisted);

        assertThat(decoded.limitations()).containsExactly(
                new SemanticAnalysisEvidence.Limitation("ABSENT_GLOBAL", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("NULL_GLOBAL", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("SOURCE_SCOPED", Optional.of("src/Order.java")));
    }

    private static MappingMongoConverter queryConverter() {
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(new SemanticQueryApplication().queryMongoCustomConversions());
        converter.afterPropertiesSet();
        return converter;
    }
}
