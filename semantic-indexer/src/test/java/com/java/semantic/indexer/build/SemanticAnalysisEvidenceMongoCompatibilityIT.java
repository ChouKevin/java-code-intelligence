package com.java.semantic.indexer.build;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Preserves the schema-v3/v1 BSON shape of global and source-scoped semantic limitations. */
class SemanticAnalysisEvidenceMongoCompatibilityIT {

    @Test
    void reads_legacy_absent_and_null_global_limitations_and_writes_the_v1_shape() {
        MappingMongoConverter converter = converter();
        Document persisted = new Document("contractVersion", 1).append("fingerprintDigest", "a".repeat(64))
                .append("buildStatus", "SUCCESS").append("projects", List.of())
                .append("resolution", new Document("attempted", 0L).append("resolved", 0L).append("unresolved", 0L)
                        .append("ambiguous", 0L).append("external", 0L))
                .append("limitations", List.of(new Document("code", "ABSENT_GLOBAL"), new Document("code", "NULL_GLOBAL")
                        .append("sourcePath", null), new Document("code", "SOURCE_SCOPED").append("sourcePath", "src/Order.java")));

        SemanticAnalysisEvidence decoded = converter.read(SemanticAnalysisEvidence.class, persisted);

        assertThat(decoded.limitations()).containsExactly(
                new SemanticAnalysisEvidence.Limitation("ABSENT_GLOBAL", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("NULL_GLOBAL", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("SOURCE_SCOPED", Optional.of("src/Order.java")));

        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(1, "a".repeat(64), "SUCCESS", List.of(),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of(
                new SemanticAnalysisEvidence.Limitation("GLOBAL", Optional.empty()),
                new SemanticAnalysisEvidence.Limitation("SCOPED", Optional.of("src/Order.java"))));
        Document written = (Document) converter.convertToMongoType(evidence);
        List<Document> limitations = written.getList("limitations", Document.class);
        assertThat(limitations.getFirst()).containsOnlyKeys("code").containsEntry("code", "GLOBAL");
        assertThat(limitations.get(1)).containsOnlyKeys("code", "sourcePath")
                .containsEntry("code", "SCOPED").containsEntry("sourcePath", "src/Order.java");
    }

    private static MappingMongoConverter converter() {
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(SemanticIndexerApplication.semanticAnalysisEvidenceMongoCustomConversions());
        converter.afterPropertiesSet();
        return converter;
    }
}
