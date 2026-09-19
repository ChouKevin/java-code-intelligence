package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Evidence that a versioned analysis environment successfully produced a generation. */
public record SemanticAnalysisEvidence(
        int contractVersion,
        String fingerprintDigest,
        String buildStatus,
        List<ProjectProof> projects,
        ResolutionCoverage resolution,
        List<Limitation> limitations) {

    public SemanticAnalysisEvidence {
        ModelValidation.require(contractVersion == IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                "unsupported semantic analysis evidence version");
        fingerprintDigest = ModelValidation.sha256(fingerprintDigest, "analysis fingerprint digest");
        buildStatus = ModelValidation.requiredText(buildStatus, "analysis build status");
        projects = List.copyOf(Objects.requireNonNull(projects, "analysis project proofs are required"));
        resolution = Objects.requireNonNull(resolution, "resolution coverage is required");
        limitations = List.copyOf(Objects.requireNonNull(limitations, "analysis limitations are required"));
    }

    public record ProjectProof(String projectPath, boolean imported, List<String> verifiedSourcePaths) {

        public ProjectProof {
            projectPath = SemanticAnalysisEvidence.projectPath(projectPath);
            verifiedSourcePaths = List.copyOf(Objects.requireNonNull(verifiedSourcePaths,
                    "verified source paths are required"));
            for (String verifiedSourcePath : verifiedSourcePaths) {
                ModelValidation.repositoryRelativePath(verifiedSourcePath);
            }
        }
    }

    public record ResolutionCoverage(long attempted, long resolved, long unresolved, long ambiguous, long external) {

        public ResolutionCoverage {
            ModelValidation.require(attempted >= 0, "attempted resolution count must not be negative");
            ModelValidation.require(resolved >= 0, "resolved resolution count must not be negative");
            ModelValidation.require(unresolved >= 0, "unresolved resolution count must not be negative");
            ModelValidation.require(ambiguous >= 0, "ambiguous resolution count must not be negative");
            ModelValidation.require(external >= 0, "external resolution count must not be negative");
        }
    }

    public record Limitation(String code, Optional<String> sourcePath) {

        public Limitation {
            code = ModelValidation.requiredText(code, "analysis limitation code");
            sourcePath = Objects.requireNonNull(sourcePath, "analysis limitation source path is required")
                    .map(ModelValidation::repositoryRelativePath);
        }
    }

    private static String projectPath(String value) {
        String path = ModelValidation.requiredText(value, "analysis project path");
        return path.equals(".") ? path : ModelValidation.repositoryRelativePath(path);
    }
}
