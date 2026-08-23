package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.model.codefact.SourceRange;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** mapper XML 走訪的收容檢查，與原始碼走訪共用同一份判準 */
class MapperXmlSqlExtractorTest {

    private static final String INSIDE_MAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.OrderMapper">
              <select id="findAll">SELECT * FROM orders</select>
            </mapper>
            """;

    private static final String OUTSIDE_MAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.VaultMapper">
              <select id="leak">SELECT secret FROM vault</select>
            </mapper>
            """;

    private static final String VARIANT_MAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.OrderMapper">
              <select id="findById" databaseId="postgres">SELECT * FROM orders WHERE id = #{id}</select>
              <select id="findById" databaseId="oracle">SELECT * FROM orders WHERE id = #{id}</select>
              <sql id="sharedColumns">id, 訂單名稱</sql>
              <select id="findDynamic">
                <if test="enabled">SELECT ${schema}.orders WHERE name = #{name}</if>
                <include refid="sharedColumns"/>
              </select>
            </mapper>
            """;

    private final MapperXmlSqlExtractor extractor = new MapperXmlSqlExtractor();

    @Test
    void should_refuse_a_mapper_xml_when_a_symlink_resolves_outside_the_repository(@TempDir Path tempDir)
            throws IOException {
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path leaked = outside.resolve("VaultMapper.xml");
        Files.writeString(leaked, OUTSIDE_MAPPER);

        Path repositoryRoot = tempDir.resolve("order-service");
        Path resourceRoot = repositoryRoot.resolve("src/main/resources");
        Path mapperDir = resourceRoot.resolve("mapper");
        Files.createDirectories(mapperDir);
        Files.writeString(mapperDir.resolve("OrderMapper.xml"), INSIDE_MAPPER);
        Files.createSymbolicLink(mapperDir.resolve("VaultMapper.xml"), leaked);

        MapperXmlSqlExtractor.SqlIndex index = extractor.index(repositoryRoot, List.of(resourceRoot));

        assertThat(index.find("com.example.VaultMapper", "leak"))
                .as("原始碼走訪擋得住的 symlink，XML 走訪同樣要擋，否則 repo 外的 SQL 會進索引再進 LLM")
                .isEmpty();
        assertThat(index.find("com.example.OrderMapper", "findAll"))
                .contains("SELECT * FROM orders");
    }

    @Test
    void should_accept_a_mapper_xml_when_a_symlink_resolves_back_inside_the_repository(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path resourceRoot = repositoryRoot.resolve("src/main/resources");
        Path mapperDir = resourceRoot.resolve("mapper");
        Path shared = repositoryRoot.resolve("shared");
        Files.createDirectories(mapperDir);
        Files.createDirectories(shared);
        Path target = shared.resolve("OrderMapper.xml");
        Files.writeString(target, INSIDE_MAPPER);
        Files.createSymbolicLink(mapperDir.resolve("OrderMapper.xml"), target);

        MapperXmlSqlExtractor.SqlIndex index = extractor.index(repositoryRoot, List.of(resourceRoot));

        assertThat(index.find("com.example.OrderMapper", "findAll"))
                .contains("SELECT * FROM orders");
    }

    @Test
    void should_index_each_mapper_variant_as_raw_unevaluated_evidence_in_document_order(@TempDir Path tempDir)
            throws IOException {
        Path repositoryRoot = tempDir.resolve("order-service");
        Path resourceRoot = repositoryRoot.resolve("src/main/resources");
        Path mapperPath = resourceRoot.resolve("mapper/OrderMapper.xml");
        Files.createDirectories(mapperPath.getParent());
        Files.writeString(mapperPath, VARIANT_MAPPER);

        MapperXmlSqlExtractor.Extraction extraction = extractor.extract(repositoryRoot, List.of(resourceRoot));
        MapperEvidenceIndex evidenceIndex = extraction.evidenceIndex();
        assertThat(MapperStatementKey.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("namespace", "statementId");
        assertThat(MapperStatementIdentity.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "statementKey",
                        "resourcePath",
                        "databaseId",
                        "documentOrdinal",
                        "representation");
        MapperStatementIdentity postgresIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findById"),
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                0,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity oracleIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findById"),
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.of("oracle"),
                1,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperStatementIdentity dynamicIdentity = new MapperStatementIdentity(
                new MapperStatementKey("com.example.OrderMapper", "findDynamic"),
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.empty(),
                3,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        MapperFragmentIdentity fragmentIdentity = new MapperFragmentIdentity(
                "com.example.OrderMapper",
                "sharedColumns",
                "src/main/resources/mapper/OrderMapper.xml",
                2,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);

        assertThat(evidenceIndex.statement(postgresIdentity)).isPresent();
        assertThat(evidenceIndex.statement(oracleIdentity)).isPresent();
        assertThat(evidenceIndex.statement(dynamicIdentity).orElseThrow().location().sourceFile())
                .isEqualTo(dynamicIdentity.resourcePath());
        assertThat(evidenceIndex.statement(dynamicIdentity).orElseThrow().includeRefIds())
                .containsExactly("sharedColumns");
        assertThat(segmentOf(VARIANT_MAPPER, evidenceIndex.statement(dynamicIdentity).orElseThrow().location()))
                .isEqualTo("""
                        <select id="findDynamic">
                            <if test="enabled">SELECT ${schema}.orders WHERE name = #{name}</if>
                            <include refid="sharedColumns"/>
                          </select>""");
        assertThat(segmentOf(VARIANT_MAPPER, evidenceIndex.fragment(fragmentIdentity).orElseThrow().location()))
                .isEqualTo("<sql id=\"sharedColumns\">id, 訂單名稱</sql>");
        assertThat(extraction.sqlIndex().find("com.example.OrderMapper", "findById"))
                .contains("SELECT * FROM orders WHERE id = #{id}");
        MapperStatementEvidence postgresEvidence = evidenceIndex.statement(postgresIdentity).orElseThrow();
        assertThatIllegalStateException().isThrownBy(() -> new MapperEvidenceIndex(
                List.of(postgresEvidence, postgresEvidence), List.of()));
    }

    private static String segmentOf(String source, SourceRange location) {
        int start = offsetOf(source, location.range().start().line(), location.range().start().character());
        int end = offsetOf(source, location.range().end().line(), location.range().end().character());
        return source.substring(start, end);
    }

    private static int offsetOf(String source, int line, int character) {
        int offset = 0;
        for (int currentLine = 0; currentLine < line; currentLine++) {
            offset = source.indexOf('\n', offset) + 1;
        }
        return offset + character;
    }
}
