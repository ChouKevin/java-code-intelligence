package com.java.semantic.indexer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.java.semantic.model.source.SourceEvidencePolicy;
import java.util.Optional;
import java.util.Set;
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
    void imported_source_plan_rejects_guessed_root_and_preserves_exact_selected_files() throws Exception {
        Path selected = repository.resolve("src/main/java/example/Order.java");
        Path unrelated = repository.resolve("src/test/java/example/Hidden.java");
        Files.createDirectories(selected.getParent());
        Files.createDirectories(unrelated.getParent());
        Files.writeString(selected, "class Order {}");
        Files.writeString(unrelated, "class Hidden {}");
        FullIndexPlanner planner = new FullIndexPlanner();

        assertThrows(IllegalArgumentException.class, () -> ImportedSourcePolicy.from(
                planner.plan(repository), Optional.empty()));
        SourceEvidencePolicy policy = ImportedSourcePolicy.from(
                planner.plan(repository, List.of(repository.resolve("src/main/java"))), Optional.empty());
        assertEquals(Set.of("src/main/java/example/Order.java"), policy.selectedCodePaths());
        assertTrue(policy.allowsCode("src/main/java/example/Order.java"));
        assertFalse(policy.allowsCode("src/test/java/example/Hidden.java"));
    }


    @Test
    void admits_standard_mybatis_public_doctype_without_resolving_external_entities() throws Exception {
        Path mapper = repository.resolve("src/main/resources/org/mybatis/jpetstore/persistence/OrderMapper.xml");
        Path config = repository.resolve("src/main/resources/config.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                    "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="org.mybatis.jpetstore.persistence.OrderMapper">
                  <select id="read">select 1</select>
                </mapper>
                """);
        Files.writeString(config, "<configuration><secret>SECRET_MARKER</secret></configuration>");

        assertEquals(List.of("src/main/resources/org/mybatis/jpetstore/persistence/OrderMapper.xml"),
                new FullIndexPlanner().plan(repository).sources().stream()
                        .map(FullIndexPlan.SourceInput::sourcePath).toList());
    }
    @Test
    void mapper_eligibility_never_expands_external_entity_into_imported_content() throws Exception {
        Path secret = repository.resolve("private.txt");
        Path mapper = repository.resolve("src/main/resources/OrderMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(secret, "SECRET_MARKER");
        Files.writeString(mapper, "<!DOCTYPE mapper [<!ENTITY secret SYSTEM \"" + secret.toUri()
                + "\">]><mapper namespace=\"example.Order\">&secret;</mapper>");

        assertFalse(new FullIndexPlanner().plan(repository).sources().stream()
                .anyMatch(source -> source.contentArtifact().utf8Content().contains("SECRET_MARKER")));
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
