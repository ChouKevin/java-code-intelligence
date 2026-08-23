package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureRevisionResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashes_repository_relative_path_content_and_length_independently_of_host_path() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("one/src"));
        Path second = Files.createDirectories(temporaryDirectory.resolve("two/src"));
        Files.writeString(first.resolve("App.java"), "class App {}\n");
        Files.writeString(second.resolve("App.java"), "class App {}\n");

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();
        RepositoryRevision firstRevision = resolver.resolve(first.getParent());
        RepositoryRevision secondRevision = resolver.resolve(second.getParent());

        assertThat(firstRevision.value()).matches("[0-9a-f]{40}").isEqualTo(secondRevision.value());
    }

    @Test
    void includes_build_and_annotation_processor_configuration_but_excludes_generated_and_vcs_files() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve("target/generated-sources"));
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("lombok.config"), "lombok.addLombokGeneratedAnnotation = true\n");
        Files.writeString(project.resolve("target/generated-sources/App.java"), "generated one\n");
        Files.writeString(project.resolve(".git/HEAD"), "ref: refs/heads/main\n");

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();
        RepositoryRevision initial = resolver.resolve(project);
        Files.writeString(project.resolve("target/generated-sources/App.java"), "generated two\n");
        Files.writeString(project.resolve(".git/HEAD"), "ref: refs/heads/other\n");
        assertThat(resolver.resolve(project)).isEqualTo(initial);

        Files.writeString(project.resolve("lombok.config"), "lombok.addLombokGeneratedAnnotation = false\n");
        assertThat(resolver.resolve(project)).isNotEqualTo(initial);
    }

    @Test
    void excludes_generated_directories_at_every_module_path_segment() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("multi-module"));
        Path service = Files.createDirectories(project.resolve("module-service/src/main/java"));
        Path target = Files.createDirectories(project.resolve("module-service/target/generated-sources"));
        Path build = Files.createDirectories(project.resolve("module-web/build/generated"));
        Files.writeString(service.resolve("App.java"), "class App {}\n");
        Files.writeString(target.resolve("Generated.java"), "class First {}\n");
        Files.writeString(build.resolve("Generated.java"), "class First {}\n");

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();
        RepositoryRevision initial = resolver.resolve(project);
        Files.writeString(target.resolve("Generated.java"), "class Second {}\n");
        Files.writeString(build.resolve("Generated.java"), "class Second {}\n");

        assertThat(resolver.resolve(project)).isEqualTo(initial);
    }

    @Test
    void includes_resources_and_build_wrappers_as_planner_inputs() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("planner-inputs"));
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(resources.resolve("application.conf"), "feature = false\n");

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();
        RepositoryRevision initial = resolver.resolve(project);

        Files.writeString(resources.resolve("application.conf"), "feature = true\n");
        RepositoryRevision resourceChanged = resolver.resolve(project);
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n# wrapper update\n");

        assertThat(resourceChanged).isNotEqualTo(initial);
        assertThat(resolver.resolve(project)).isNotEqualTo(resourceChanged);
    }

    @Test
    void rejects_unicode_normalization_collisions_before_hashing() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("unicode-collision"));
        Files.writeString(project.resolve("Caf\u00e9.java"), "class One {}\n");
        Files.writeString(project.resolve("Cafe\u0301.java"), "class Two {}\n");

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();

        assertThatThrownBy(() -> resolver.resolve(project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unicode-normalization collision");
    }

    @Test
    void rejects_symlinked_inputs() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createTempFile(temporaryDirectory, "outside", ".java");
        Files.writeString(outside, "class Outside {}\n");
        try {
            Files.createSymbolicLink(project.resolve("linked.java"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        FixtureRevisionResolver resolver = new FixtureRevisionResolver();
        assertThatThrownBy(() -> resolver.resolve(project)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unreadable_inputs_before_planning() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("unreadable"));
        Path source = project.resolve("App.java");
        Files.writeString(source, "class App {}\n");
        assumeTrue(Files.getFileStore(source).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(source);
        Files.setPosixFilePermissions(source, Set.of());
        try {
            assertThatThrownBy(() -> new FixtureRevisionResolver().resolve(project))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unavailable or unsafe");
        } finally {
            Files.setPosixFilePermissions(source, originalPermissions);
        }
    }
}
