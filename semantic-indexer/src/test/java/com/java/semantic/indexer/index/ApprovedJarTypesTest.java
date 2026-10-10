package com.java.semantic.indexer.index;

import com.java.semantic.model.index.RelationContract.Fact;
import com.java.semantic.model.index.RelationContract.Kind;
import com.java.semantic.model.index.RelationContract.ResolutionState;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedJarTypesTest {
    @TempDir Path temporary;

    @Test void two_explicit_dependency_versions_resolve_their_own_signatures_and_never_load_target_code() throws Exception {
        byte[] first = compile("first", "String");
        byte[] second = compile("second", "Object");
        Path one = Files.createDirectories(temporary.resolve("one"));
        Path two = Files.createDirectories(temporary.resolve("two"));
        Path jarOne = jar(one.resolve("dependency-000.jar"), false, Map.of("dep/Api.class", first));
        Path jarTwo = jar(two.resolve("dependency-000.jar"), false, Map.of("dep/Api.class", second));
        Map<String, String> source = Map.of("Use.java", "import dep.Api; class Use { void run() { dep.Api.accept(\"x\"); Api.accept(\"x\"); } }");
        try (RelationExtractionFixture a = new RelationExtractionFixture(one, source, List.of(dependency(jarOne)), List.of(""));
                RelationExtractionFixture b = new RelationExtractionFixture(two, source, List.of(dependency(jarTwo)), List.of(""))) {
            Fact old = call(a);
            List<Fact> references = a.extract().stream().filter(fact -> fact.kind() == Kind.REFERENCE).toList();
            assertThat(references).extracting(a::slice).containsExactly("dep.Api", "Api");
            assertThat(references).allSatisfy(reference -> {
                assertThat(reference.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
                assertThat(reference.target().orElseThrow().external().orElseThrow().qualifiedOwner()).isEqualTo("dep.Api");
                assertThat(reference.target().orElseThrow().external().orElseThrow().artifactDigest())
                        .isEqualTo(IndexFiles.digest(jarOne));
            });
            Fact next = call(b);
            assertThat(old.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(next.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(old.target().orElseThrow().external().orElseThrow().signature()).isEqualTo("dep.Api.accept(java.lang.String)");
            assertThat(next.target().orElseThrow().external().orElseThrow().signature()).isEqualTo("dep.Api.accept(java.lang.Object)");
            assertThat(old.target().orElseThrow().external().orElseThrow().artifactDigest()).isEqualTo(IndexFiles.digest(jarOne));
            assertThat(next.target().orElseThrow().external().orElseThrow().artifactDigest()).isEqualTo(IndexFiles.digest(jarTwo));
            assertThat(old.target().orElseThrow().external().orElseThrow().targetId())
                    .isNotEqualTo(next.target().orElseThrow().external().orElseThrow().targetId());
        }
    }

    @Test void ordered_approved_jars_choose_first_provider_and_admitted_source_takes_precedence() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("precedence"));
        Path firstJar = jar(directory.resolve("dependency-000.jar"), false, Map.of("dep/Api.class", compile("ordered-first", "Object")));
        Path secondJar = jar(directory.resolve("dependency-001.jar"), false, Map.of("dep/Api.class", compile("ordered-second", "String")));
        List<ResolutionPlan.Dependency> dependencies = List.of(
                new ResolutionPlan.Dependency("first-api", firstJar.getFileName().toString(), IndexFiles.digest(firstJar), Files.size(firstJar), 0),
                new ResolutionPlan.Dependency("second-api", secondJar.getFileName().toString(), IndexFiles.digest(secondJar), Files.size(secondJar), 1));
        String use = "class Use { void run() { dep.Api.accept(\"x\"); } }";
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(directory, Map.of("Use.java", use), dependencies, List.of(""))) {
            Fact selected = call(fixture);
            assertThat(selected.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(selected.target().orElseThrow().external().orElseThrow().signature()).isEqualTo("dep.Api.accept(java.lang.Object)");
            assertThat(selected.target().orElseThrow().external().orElseThrow().artifactId()).isEqualTo("first-api");
            assertThat(selected.target().orElseThrow().external().orElseThrow().artifactDigest()).isEqualTo(IndexFiles.digest(firstJar));
            assertThat(selected.target().orElseThrow().source()).isEmpty();
        }
        Map<String, String> admitted = Map.of("Use.java", use,
                "dep/Api.java", "package dep; public class Api { public static void accept(CharSequence value) {} }");
        try (RelationExtractionFixture fixture = new RelationExtractionFixture(directory, admitted, dependencies, List.of(""))) {
            Fact selected = call(fixture);
            assertThat(selected.resolutionState()).isEqualTo(ResolutionState.STATIC_RESOLVED);
            assertThat(selected.target().orElseThrow().source().orElseThrow().qualifiedSignature()).contains("dep.Api.accept(java.lang.CharSequence)");
            assertThat(selected.target().orElseThrow().source().orElseThrow().declaration().path()).isEqualTo("dep/Api.java");
            assertThat(selected.target().orElseThrow().source().orElseThrow().declaration().symbolId())
                    .isEqualTo(fixture.units.get("dep/Api.java").syntax().declarations().stream()
                            .filter(declaration -> declaration.name().equals("accept")).findFirst().orElseThrow().symbolId());
            assertThat(selected.target().orElseThrow().external()).isEmpty();
        }
    }

    @Test void multi_release_selection_requires_manifest_and_uses_highest_approved_java21_entry() throws Exception {
        byte[] base = compile("base", "Object");
        byte[] release = compile("release", "String");
        Map<String, byte[]> entries = Map.of("dep/Api.class", base, "META-INF/versions/21/dep/Api.class", release,
                "META-INF/versions/22/dep/Api.class", base);
        Map<String, String> source = Map.of("Use.java", "class Use { void run() { dep.Api.accept(\"x\"); } }");
        for (boolean multi : List.of(false, true)) {
            Path directory = Files.createDirectories(temporary.resolve("multi-" + multi));
            Path jar = jar(directory.resolve("dependency-000.jar"), multi, entries);
            try (RelationExtractionFixture fixture = new RelationExtractionFixture(directory, source, List.of(dependency(jar)), List.of(""))) {
                assertThat(call(fixture).target().orElseThrow().external().orElseThrow().signature())
                        .isEqualTo(multi ? "dep.Api.accept(java.lang.String)" : "dep.Api.accept(java.lang.Object)");
            }
        }
    }

    @Test void unsafe_nested_and_oversized_class_archives_fail_instead_of_becoming_partial_environments() throws Exception {
        List<Map<String, byte[]>> archives = List.of(Map.of("../outside.class", new byte[] {1}),
                Map.of("BOOT-INF/classes/dep/Api.class", new byte[] {1}),
                Map.of("lib/inner.jar", new byte[] {1}),
                Map.of("dep/Api.class", new byte[ApprovedJarTypes.MAX_CLASS_BYTES + 1]));
        for (int index = 0; index < archives.size(); index++) {
            Path directory = Files.createDirectories(temporary.resolve("invalid-" + index));
            Path jar = jar(directory.resolve("dependency-000.jar"), false, archives.get(index));
            List<ResolutionPlan.Dependency> dependencies = List.of(dependency(jar));
            assertThatThrownBy(() -> {
                try (RelationExtractionFixture fixture = new RelationExtractionFixture(directory,
                        Map.of("Use.java", "class Use {}"), dependencies, List.of(""))) {
                    fixture.extract();
                }
            }).isInstanceOf(IOException.class);
        }
    }

    private byte[] compile(String name, String parameter) throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("compile-" + name));
        Path source = directory.resolve("Api.java");
        Files.writeString(source, "package dep; public class Api { static { if (true) throw new AssertionError(\"must not run\"); }"
                + " public static void accept(" + parameter + " value) {} }");
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none", "-d", directory.toString(), source.toString());
        assertThat(result).isZero();
        return Files.readAllBytes(directory.resolve("dep/Api.class"));
    }

    private static Path jar(Path path, boolean multiRelease, Map<String, byte[]> contents) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (OutputStream stream = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(stream, manifest)) {
            for (Map.Entry<String, byte[]> content : contents.entrySet()) {
                jar.putNextEntry(new JarEntry(content.getKey()));
                jar.write(content.getValue());
                jar.closeEntry();
            }
        }
        return path;
    }

    private static ResolutionPlan.Dependency dependency(Path jar) throws IOException {
        return new ResolutionPlan.Dependency("approved-api", jar.getFileName().toString(), IndexFiles.digest(jar), Files.size(jar), 0);
    }

    private static Fact call(RelationExtractionFixture fixture) {
        return fixture.extract().stream().filter(fact -> fact.kind() == Kind.CALL && fixture.slice(fact).equals("dep.Api.accept(\"x\")"))
                .findFirst().orElseThrow();
    }
}
