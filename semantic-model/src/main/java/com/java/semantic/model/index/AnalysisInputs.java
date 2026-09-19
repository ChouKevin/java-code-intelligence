package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact effective semantic-analysis inputs. Paths are repository-relative and
 * artifact contents are represented by independently computed SHA-256 digests.
 */
public record AnalysisInputs(
        int contractVersion,
        String analyzerDigest,
        String jdtLsDigest,
        String launcherJdkDigest,
        String importInputsDigest,
        List<Project> projects) {

    public AnalysisInputs {
        ModelValidation.require(contractVersion == IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                "unsupported analysis inputs contract version");
        analyzerDigest = ModelValidation.sha256(analyzerDigest, "analyzer digest");
        jdtLsDigest = ModelValidation.sha256(jdtLsDigest, "JDT LS digest");
        launcherJdkDigest = ModelValidation.sha256(launcherJdkDigest, "launcher JDK digest");
        importInputsDigest = ModelValidation.sha256(importInputsDigest, "import inputs digest");
        projects = List.copyOf(Objects.requireNonNull(projects, "analysis projects are required"));
    }

    public record Project(
            String projectPath,
            String projectJdkDigest,
            Map<String, String> compilerOptions,
            List<String> selectedProfiles,
            List<Root> roots,
            List<Artifact> classpath,
            List<Artifact> modulepath) {

        public Project {
            projectPath = AnalysisInputs.projectPath(projectPath);
            projectJdkDigest = ModelValidation.sha256(projectJdkDigest, "project JDK digest");
            compilerOptions = Map.copyOf(Objects.requireNonNull(compilerOptions, "compiler options are required"));
            compilerOptions.forEach((key, value) -> {
                ModelValidation.requiredText(key, "compiler option key");
                ModelValidation.requiredText(value, "compiler option value");
            });
            selectedProfiles = List.copyOf(Objects.requireNonNull(selectedProfiles, "selected profiles are required"));
            for (String selectedProfile : selectedProfiles) {
                ModelValidation.requiredText(selectedProfile, "selected profile");
            }
            roots = List.copyOf(Objects.requireNonNull(roots, "analysis roots are required"));
            classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath artifacts are required"));
            modulepath = List.copyOf(Objects.requireNonNull(modulepath, "modulepath artifacts are required"));
        }
    }

    public record Root(String path, String kind, boolean included, List<String> exclusions) {

        public Root {
            path = projectPath(path);
            kind = ModelValidation.requiredText(kind, "analysis root kind");
            exclusions = List.copyOf(Objects.requireNonNull(exclusions, "analysis root exclusions are required"));
            for (String exclusion : exclusions) {
                projectPath(exclusion);
            }
        }
    }

    public record Artifact(int ordinal, String logicalId, String kind, String contentDigest, long byteLength) {

        public Artifact {
            ModelValidation.require(ordinal >= 0, "artifact ordinal must not be negative");
            logicalId = ModelValidation.requiredText(logicalId, "artifact logical id");
            kind = ModelValidation.requiredText(kind, "artifact kind");
            ModelValidation.require(!isLocalArtifactLocation(logicalId),
                    "artifact logical id must not contain a local filesystem location");
            contentDigest = ModelValidation.sha256(contentDigest, "artifact content digest");
            ModelValidation.require(byteLength >= 0, "artifact byte length must not be negative");
        }
    }

    private static boolean isLocalArtifactLocation(String value) {
        boolean windowsDrive = value.length() >= 3 && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':' && (value.charAt(2) == '/' || value.charAt(2) == '\\');
        boolean localFileUri = value.length() >= 5 && value.regionMatches(true, 0, "file:", 0, 5);
        return value.startsWith("/") || value.startsWith("\\") || localFileUri || windowsDrive;
    }

    private static String projectPath(String value) {
        String path = ModelValidation.requiredText(value, "analysis project path");
        return path.equals(".") ? path : ModelValidation.repositoryRelativePath(path);
    }
}
