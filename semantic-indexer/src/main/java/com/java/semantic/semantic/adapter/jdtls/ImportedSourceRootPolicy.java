package com.java.semantic.semantic.adapter.jdtls;

import com.java.semantic.model.index.AnalysisInputs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared imported-root inventory and inclusion policy for readiness and analysis inputs. */
final class ImportedSourceRootPolicy {

    private ImportedSourceRootPolicy() {
    }

    static List<Root> inventory(Path repositoryRoot, Path projectRoot, List<String> sourcePaths) {
        Path repository = realPath(Objects.requireNonNull(repositoryRoot, "repository root is required"), "repository root");
        Path project = realPath(Objects.requireNonNull(projectRoot, "project root is required"), "project root");
        if (!project.startsWith(repository)) {
            throw new IllegalStateException("JDT LS project root escaped the repository");
        }
        List<Root> roots = new ArrayList<>();
        for (String sourcePath : Objects.requireNonNull(sourcePaths, "source paths are required")) {
            Path candidate = project.resolve(sourcePath).normalize();
            boolean contained = candidate.startsWith(repository);
            String projectRelativePath = relative(project, candidate);
            boolean test = projectRelativePath.contains("/test/") || projectRelativePath.startsWith("src/test/");
            boolean generated = projectRelativePath.contains("generated");
            boolean symlink = contained && containsSymbolicLink(repository, candidate);
            boolean escaped = contained && !symlink && realPathEscapes(repository, candidate);
            List<String> exclusions = exclusions(test, generated, contained, symlink, escaped);
            String relative = contained ? relative(repository, candidate) : relative(repository, project);
            boolean included = contained && !test && !generated && !symlink && !escaped;
            roots.add(new Root(candidate, new AnalysisInputs.Root(relative, "SOURCE", included, exclusions)));
        }
        return List.copyOf(roots);
    }

    private static Path realPath(Path path, String description) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("JDT LS " + description + " was not a usable local path", exception);
        }
    }

    private static boolean containsSymbolicLink(Path repository, Path candidate) {
        for (Path segment : repository.relativize(candidate)) {
            repository = repository.resolve(segment);
            if (Files.isSymbolicLink(repository)) {
                return true;
            }
        }
        return false;
    }

    private static boolean realPathEscapes(Path repository, Path candidate) {
        if (!Files.exists(candidate)) {
            return false;
        }
        try {
            return !candidate.toRealPath().startsWith(repository);
        } catch (IOException exception) {
            return true;
        }
    }

    private static List<String> exclusions(
            boolean test, boolean generated, boolean contained, boolean symlink, boolean escaped) {
        List<String> exclusions = new ArrayList<>();
        if (test) {
            exclusions.add("test");
        }
        if (generated) {
            exclusions.add("generated");
        }
        if (!contained || escaped) {
            exclusions.add("outside-repository");
        }
        if (symlink) {
            exclusions.add("symlink");
        }
        return List.copyOf(exclusions);
    }

    private static String relative(Path repository, Path path) {
        return repository.relativize(path).toString().replace('\\', '/');
    }

    record Root(Path path, AnalysisInputs.Root analysisRoot) {
        Root {
            path = Objects.requireNonNull(path, "source root path is required");
            analysisRoot = Objects.requireNonNull(analysisRoot, "analysis root is required");
        }
    }
}
