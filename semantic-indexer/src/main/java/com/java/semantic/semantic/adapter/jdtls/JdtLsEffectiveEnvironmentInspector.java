package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.repository.domain.RepositorySnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.springframework.util.Assert;

/** Reads JDT LS's post-import project model and converts it into persistable, path-free inputs. */
public final class JdtLsEffectiveEnvironmentInspector {
    private static final String GET_ALL = "java.project.getAll";
    private static final String GET_SETTINGS = "java.project.getSettings";
    private static final String GET_CLASSPATHS = "java.project.getClasspaths";
    private static final String NATURE_IDS = "org.eclipse.jdt.ls.core.natureIds";
    private static final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";
    private static final String SELECTED_PROFILES = "org.eclipse.m2e.core.selectedProfiles";
    private static final List<String> SETTINGS = List.of(
            "org.eclipse.jdt.core.compiler.source",
            "org.eclipse.jdt.core.compiler.compliance",
            "org.eclipse.jdt.core.compiler.codegen.targetPlatform",
            "org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures",
            "org.eclipse.jdt.core.compiler.debug.localVariable",
            "org.eclipse.jdt.core.compiler.debug.lineNumber",
            "org.eclipse.jdt.core.compiler.debug.sourceFile",
            "org.eclipse.jdt.core.compiler.release",
            "org.eclipse.jdt.core.compiler.annotation.nullanalysis",
            "org.eclipse.jdt.ls.core.vm.location",
            "org.eclipse.jdt.ls.core.sourcePaths",
            "org.eclipse.jdt.ls.core.classpathEntries",
            "org.eclipse.jdt.ls.core.outputPath",
            NATURE_IDS);

    private final JdtLsProperties properties;

    public JdtLsEffectiveEnvironmentInspector(JdtLsProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    public AnalysisInputs inspect(JdtWorkspaceSession session, RepositorySnapshot snapshot) {
        Objects.requireNonNull(session, "session is required");
        Objects.requireNonNull(snapshot, "snapshot is required");
        Assert.isTrue(session.repositoryId().equals(snapshot.repositoryId()), "session repository must match snapshot");
        Assert.isTrue(session.revision().equals(snapshot.revision()), "session revision must match snapshot");
        if (!session.isUsable()) {
            throw new IllegalStateException("cannot inspect a closed JDT workspace session");
        }
        List<String> projectUris = projectUris(session);
        if (projectUris.isEmpty()) {
            throw new IllegalStateException("JDT LS reported no imported Java projects");
        }
        List<AnalysisInputs.Project> projects = new ArrayList<>();
        for (String projectUri : projectUris) {
            Path projectRoot = containedProjectRoot(projectUri, snapshot.root());
            Map<String, Object> settings = objectMap(command(session, new ExecuteCommandParams(
                    GET_SETTINGS, List.of(projectUri, settingsKeys()))), GET_SETTINGS);
            if (isMavenProject(settings)) {
                Map<String, Object> profileSettings = objectMap(command(session, new ExecuteCommandParams(
                        GET_SETTINGS, List.of(projectUri, List.of(SELECTED_PROFILES)))), GET_SETTINGS);
                settings = merge(settings, profileSettings);
            }
            ExecuteCommandParams classpathCommand = new ExecuteCommandParams(
                    GET_CLASSPATHS, new ArrayList<>(List.of(projectUri, "{\"scope\":\"runtime\"}")));
            Map<String, Object> classpaths = objectMap(command(session, classpathCommand), GET_CLASSPATHS);
            assertClasspathProjectRoot(classpaths, projectRoot, snapshot.root());
            List<String> sourcePaths = requiredStringList(settings, "org.eclipse.jdt.ls.core.sourcePaths");
            List<String> classpathEntries = requiredStringList(classpaths, "classpaths");
            List<String> modulepathEntries = requiredStringList(classpaths, "modulepaths");
            List<Path> projectEdges = projectOutputPaths(settings);
            projects.add(new AnalysisInputs.Project(relative(snapshot.root(), projectRoot),
                    projectJdkDigest(settings), compilerOptions(settings), selectedProfiles(settings),
                    roots(snapshot.root(), projectRoot, sourcePaths),
                    artifacts(snapshot, classpathEntries, "CLASSPATH", projectEdges),
                    artifacts(snapshot, modulepathEntries, "MODULEPATH", projectEdges)));
        }
        return new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                digestText(getClass().getName()), digestDirectory(properties.getHome()),
                digestDirectory(javaHome(properties.getJavaExecutable())), importInputsDigest(snapshot), projects);
    }

    private List<String> projectUris(JdtWorkspaceSession session) {
        Object response = command(session, new ExecuteCommandParams(GET_ALL, List.of()));
        List<Object> values = list(response, GET_ALL);
        List<String> uris = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String uri) || uri.isBlank()) {
                throw new IllegalStateException("JDT LS returned a non-URI project entry");
            }
            uris.add(uri);
        }
        return List.copyOf(uris);
    }

    private Object command(JdtWorkspaceSession session, ExecuteCommandParams params) {
        return session.call("workspace/executeCommand:" + params.getCommand(),
                server -> server.getWorkspaceService().executeCommand(params));
    }

    private Path containedProjectRoot(String projectUri, Path repositoryRoot) {
        try {
            Path root = Path.of(URI.create(projectUri)).toRealPath();
            Path repository = repositoryRoot.toRealPath();
            if (!root.startsWith(repository)) {
                throw new IllegalStateException("JDT LS project root escaped the repository");
            }
            return root;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("JDT LS returned an unusable project root", exception);
        }
    }

    private Map<String, String> compilerOptions(Map<String, Object> settings) {
        Map<String, String> options = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            if (entry.getKey().startsWith("org.eclipse.jdt.core.compiler.")
                    && entry.getValue() instanceof String value && !value.isBlank()) {
                options.put(entry.getKey(), value);
            }
        }
        options.put("analysis.m2e.selectedProfiles.applicability",
                isMavenProject(settings) ? "APPLICABLE" : "NOT_APPLICABLE");
        return Map.copyOf(options);
    }

    private List<String> selectedProfiles(Map<String, Object> settings) {
        if (!isMavenProject(settings)) {
            return List.of();
        }
        Object selected = settings.get(SELECTED_PROFILES);
        if (selected instanceof String profiles) {
            if (profiles.isBlank()) {
                return List.of();
            }
            return List.of(profiles.split(",", -1));
        }
        return stringList(selected);
    }

    private List<String> settingsKeys() {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(SETTINGS);
        keys.addAll(JavaCore.getOptions().keySet());
        return List.copyOf(keys);
    }

    private boolean isMavenProject(Map<String, Object> settings) {
        return stringList(settings.get(NATURE_IDS)).contains(MAVEN_NATURE);
    }

    private static Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return Map.copyOf(merged);
    }

    private void assertClasspathProjectRoot(Map<String, Object> classpaths, Path projectRoot, Path repositoryRoot) {
        Object value = classpaths.get("projectRoot");
        if (!(value instanceof String rootUri) || rootUri.isBlank()) {
            throw new IllegalStateException("JDT LS classpath response did not include projectRoot");
        }
        Path classpathRoot = containedProjectRoot(rootUri, repositoryRoot);
        if (!classpathRoot.equals(projectRoot)) {
            throw new IllegalStateException("JDT LS classpath response was for a different project root");
        }
    }

    private List<AnalysisInputs.Root> roots(
            Path repositoryRoot, Path projectRoot, List<String> sourcePaths) {
        List<AnalysisInputs.Root> roots = new ArrayList<>();
        for (String sourcePath : sourcePaths) {
            Path candidate = projectRoot.resolve(sourcePath).normalize();
            boolean contained = candidate.startsWith(repositoryRoot.toAbsolutePath().normalize());
            String relative = contained ? relative(repositoryRoot, candidate) : relative(repositoryRoot, projectRoot);
            boolean test = sourcePath.contains("/test/") || sourcePath.startsWith("src/test/");
            boolean generated = sourcePath.contains("generated");
            roots.add(new AnalysisInputs.Root(relative, "SOURCE", contained && !test && !generated,
                    exclusions(test, generated, contained)));
        }
        return List.copyOf(roots);
    }

    private List<String> exclusions(boolean test, boolean generated, boolean contained) {
        List<String> exclusions = new ArrayList<>();
        if (test) {
            exclusions.add("test");
        }
        if (generated) {
            exclusions.add("generated");
        }
        if (!contained) {
            exclusions.add("outside-repository");
        }
        return List.copyOf(exclusions);
    }

    private List<AnalysisInputs.Artifact> artifacts(
            RepositorySnapshot snapshot, List<String> entries, String kind, List<Path> projectEdges) {
        List<AnalysisInputs.Artifact> artifacts = new ArrayList<>();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            Path artifact = path(entries.get(ordinal));
            if (projectEdges.contains(artifact)) {
                artifacts.add(new AnalysisInputs.Artifact(ordinal,
                        "project:" + relative(snapshot.root(), artifact), "PROJECT_EDGE",
                        digestText("project-edge:" + snapshot.revision().value()), 0));
            } else {
                artifacts.add(new AnalysisInputs.Artifact(ordinal, logicalArtifactId(artifact), kind,
                        digestPath(artifact), byteLength(artifact)));
            }
        }
        return List.copyOf(artifacts);
    }

    private List<Path> projectOutputPaths(Map<String, Object> settings) {
        Object value = settings.get("org.eclipse.jdt.ls.core.classpathEntries");
        if (Objects.isNull(value)) {
            throw new IllegalStateException("JDT LS settings omitted classpath entries");
        }
        List<Object> entries = list(value, "classpath entries");
        List<Path> outputs = new ArrayList<>();
        Object defaultOutput = settings.get("org.eclipse.jdt.ls.core.outputPath");
        if (defaultOutput instanceof String outputPath && !outputPath.isBlank()) {
            outputs.add(path(outputPath));
        }
        for (Object entry : entries) {
            Map<String, Object> classpathEntry = objectMap(entry, "classpath entry");
            Object kind = classpathEntry.get("kind");
            Object output = classpathEntry.get("output");
            if (isProjectEntry(kind) && output instanceof String outputPath && !outputPath.isBlank()) {
                outputs.add(path(outputPath));
            }
        }
        return List.copyOf(outputs);
    }

    private static boolean isProjectEntry(Object kind) {
        return "3".equals(String.valueOf(kind)) || "3.0".equals(String.valueOf(kind));
    }

    private static String logicalArtifactId(Path artifact) {
        Path normalized = artifact.toAbsolutePath().normalize();
        String fileName = normalized.getFileName().toString();
        int repositorySegment = indexOf(normalized, "repository");
        if (repositorySegment >= 0 && normalized.getNameCount() > repositorySegment + 3) {
            List<String> segments = new ArrayList<>();
            for (int index = repositorySegment + 1; index < normalized.getNameCount() - 3; index++) {
                segments.add(normalized.getName(index).toString());
            }
            String artifactId = normalized.getName(normalized.getNameCount() - 3).toString();
            String version = normalized.getName(normalized.getNameCount() - 2).toString();
            return "maven:" + String.join(".", segments) + ":" + artifactId + ":" + version + ":" + fileName;
        }
        return "library:sha256:" + digestPath(normalized);
    }

    private static int indexOf(Path path, String segment) {
        for (int index = 0; index < path.getNameCount(); index++) {
            if (segment.equals(path.getName(index).toString())) {
                return index;
            }
        }
        return -1;
    }

    private static Path path(String value) {
        try {
            return Path.of(URI.create(value)).toRealPath();
        } catch (IllegalArgumentException exception) {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("JDT LS resolved artifact is unavailable", exception);
        }
    }

    private static long byteLength(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
                return paths.filter(Files::isRegularFile).mapToLong(JdtLsEffectiveEnvironmentInspector::fileSize).sum();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("unable to measure JDT LS artifact", exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("unable to measure JDT LS artifact", exception);
        }
    }

    private String projectJdkDigest(Map<String, Object> settings) {
        Object value = settings.get("org.eclipse.jdt.ls.core.vm.location");
        if (value instanceof String location && !location.isBlank()) {
            return digestDirectory(path(location));
        }
        return digestDirectory(javaHome(properties.getJavaExecutable()));
    }

    private static Path javaHome(Path executable) {
        Path normalized = executable.toAbsolutePath().normalize();
        Path bin = normalized.getParent();
        if (bin == null || bin.getParent() == null) {
            throw new IllegalStateException("configured Java executable has no Java home");
        }
        return bin.getParent();
    }

    private static String importInputsDigest(RepositorySnapshot snapshot) {
        return digestText("repository=" + snapshot.repositoryId().value() + "\nrevision=" + snapshot.revision().value()
                + "\nimport-policy=effective-jdtls-v1\nmapper-policy=xml-and-annotation");
    }

    private static Map<String, Object> objectMap(Object value, String command) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("JDT LS " + command + " response was not an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("JDT LS " + command + " response had a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object value) {
        if (Objects.isNull(value)) {
            return List.of();
        }
        List<Object> values = list(value, "list");
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("JDT LS response contained a non-string list value");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    static List<String> requiredStringList(Map<String, Object> response, String field) {
        if (!response.containsKey(field) || Objects.isNull(response.get(field))) {
            throw new IllegalStateException("JDT LS response omitted required " + field);
        }
        return stringList(response.get(field));
    }

    private static List<Object> list(Object value, String command) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("JDT LS " + command + " response was not a list");
        }
        return List.copyOf(values);
    }

    private static String relative(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IllegalStateException("JDT LS path escaped repository");
        }
        Path relative = normalizedRoot.relativize(normalizedPath);
        return relative.getNameCount() == 0 ? "." : relative.toString().replace('\\', '/');
    }

    private static String digestPath(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("JDT LS artifact is missing");
        }
        if (Files.isRegularFile(path)) {
            return digestFile(path);
        }
        return digestDirectory(path);
    }

    private static String digestDirectory(Path directory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                List<Path> files = paths.filter(Files::isRegularFile).sorted(Comparator.comparing(path ->
                        directory.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString()))
                        .toList();
                for (Path file : files) {
                    update(digest, directory.toAbsolutePath().normalize()
                            .relativize(file.toAbsolutePath().normalize()).toString());
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to digest JDT LS environment content", exception);
        }
    }

    private static String digestFile(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to digest JDT LS artifact", exception);
        }
    }

    private static String digestText(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
