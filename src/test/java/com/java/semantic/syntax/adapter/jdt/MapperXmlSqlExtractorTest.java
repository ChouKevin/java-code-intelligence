package com.java.semantic.syntax.adapter.jdt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

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
}
