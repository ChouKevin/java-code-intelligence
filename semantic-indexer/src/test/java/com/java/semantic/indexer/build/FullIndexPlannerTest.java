package com.java.semantic.indexer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullIndexPlannerTest {

    @TempDir
    Path repository;

    @Test
    void selects_supported_java_and_mapper_xml_sources_in_deterministic_path_order() throws Exception {
        Path javaSource = repository.resolve("src/main/java/example/Order.java");
        Path packageNamedBuild = repository.resolve("src/main/java/com/acme/build/BuildService.java");
        Path mapper = repository.resolve("src/main/resources/mybatis/Order.xml");
        Path buildOutput = repository.resolve("build/generated-sources/com/acme/GeneratedService.java");
        Path unrelatedXml = repository.resolve("src/main/resources/application.xml");
        Files.createDirectories(javaSource.getParent());
        Files.createDirectories(packageNamedBuild.getParent());
        Files.createDirectories(mapper.getParent());
        Files.createDirectories(buildOutput.getParent());
        Files.writeString(javaSource, "package example; class Order {}\n");
        Files.writeString(packageNamedBuild, "package com.acme.build; class BuildService {}\n");
        Files.writeString(mapper, "<mapper namespace=\"example.OrderMapper\"/>\n");
        Files.writeString(buildOutput, "package com.acme; class GeneratedService {}\n");
        Files.writeString(unrelatedXml, "<configuration><enabled>true</enabled></configuration>\n");
        Files.writeString(repository.resolve("README.md"), "ignored\n");

        FullIndexPlan plan = new FullIndexPlanner().plan(repository);

        assertEquals(List.of("src/main/java/com/acme/build/BuildService.java", "src/main/java/example/Order.java",
                        "src/main/resources/mybatis/Order.xml"),
                plan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath).toList());
        assertFalse(plan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath).anyMatch(path -> path.endsWith("application.xml")));
        assertFalse(plan.sources().stream().map(FullIndexPlan.SourceInput::sourcePath).anyMatch(path -> path.endsWith("GeneratedService.java")));
        assertTrue(plan.sources().stream().allMatch(source -> source.contentArtifact().contentHash().matches("[0-9a-f]{64}")));
    }

    @Test
    void rejects_a_supported_file_symlink_before_reading_an_escape() throws Exception {
        Path outside = Files.createTempFile("outside-index-input", ".java");
        Files.writeString(outside, "package outside; class Escaped {}\n");
        Path symlink = repository.resolve("src/main/java/example/Escaped.java");
        Files.createDirectories(symlink.getParent());
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(IllegalArgumentException.class, () -> new FullIndexPlanner().plan(repository));
    }
}
