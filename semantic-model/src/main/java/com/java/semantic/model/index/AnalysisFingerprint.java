package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** SHA-256 identity of the exact effective analysis environment. */
public record AnalysisFingerprint(AnalysisInputs inputs, String digest) {

    public AnalysisFingerprint {
        inputs = Objects.requireNonNull(inputs, "analysis inputs are required");
        digest = ModelValidation.sha256(digest, "analysis fingerprint digest");
        ModelValidation.require(digest.equals(canonicalDigest(inputs)),
                "analysis fingerprint digest must match canonical analysis inputs");
    }

    public static AnalysisFingerprint from(AnalysisInputs inputs) {
        AnalysisInputs requiredInputs = Objects.requireNonNull(inputs, "analysis inputs are required");
        return new AnalysisFingerprint(requiredInputs, canonicalDigest(requiredInputs));
    }

    private static String canonicalDigest(AnalysisInputs inputs) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            CanonicalWriter writer = new CanonicalWriter(messageDigest);
            writer.text("analysis-inputs-v1");
            writer.number(inputs.contractVersion());
            writer.text(inputs.analyzerDigest());
            writer.text(inputs.jdtLsDigest());
            writer.text(inputs.launcherJdkDigest());
            writer.text(inputs.importInputsDigest());
            List<AnalysisInputs.Project> projects = new ArrayList<>(inputs.projects());
            projects.sort(Comparator.comparing(AnalysisFingerprint::projectKey));
            writer.number(projects.size());
            for (AnalysisInputs.Project project : projects) {
                writeProject(writer, project);
            }
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void writeProject(CanonicalWriter writer, AnalysisInputs.Project project) {
        writer.text(project.projectPath());
        writer.text(project.projectJdkDigest());
        Map<String, String> compilerOptions = new TreeMap<>(project.compilerOptions());
        writer.number(compilerOptions.size());
        compilerOptions.forEach((key, value) -> {
            writer.text(key);
            writer.text(value);
        });
        writeTexts(writer, project.selectedProfiles());
        List<AnalysisInputs.Root> roots = new ArrayList<>(project.roots());
        roots.sort(Comparator.comparing(AnalysisFingerprint::rootKey));
        writer.number(roots.size());
        for (AnalysisInputs.Root root : roots) {
            writeRoot(writer, root);
        }
        writeArtifacts(writer, project.classpath());
        writeArtifacts(writer, project.modulepath());
    }

    private static void writeRoot(CanonicalWriter writer, AnalysisInputs.Root root) {
        writer.text(root.path());
        writer.text(root.kind());
        writer.text(Boolean.toString(root.included()));
        writeTexts(writer, root.exclusions());
    }

    private static void writeArtifacts(CanonicalWriter writer, List<AnalysisInputs.Artifact> artifacts) {
        writer.number(artifacts.size());
        for (AnalysisInputs.Artifact artifact : artifacts) {
            writer.number(artifact.ordinal());
            writer.text(artifact.logicalId());
            writer.text(artifact.kind());
            writer.text(artifact.contentDigest());
            writer.number(artifact.byteLength());
        }
    }

    private static void writeTexts(CanonicalWriter writer, List<String> values) {
        writer.number(values.size());
        for (String value : values) {
            writer.text(value);
        }
    }

    private static String projectKey(AnalysisInputs.Project project) {
        StringBuilder key = new StringBuilder();
        append(key, "projectPath");
        append(key, project.projectPath());
        append(key, "projectJdkDigest");
        append(key, project.projectJdkDigest());
        Map<String, String> compilerOptions = new TreeMap<>(project.compilerOptions());
        append(key, "compilerOptions");
        append(key, Integer.toString(compilerOptions.size()));
        compilerOptions.forEach((name, value) -> {
            append(key, name);
            append(key, value);
        });
        append(key, "selectedProfiles");
        append(key, Integer.toString(project.selectedProfiles().size()));
        project.selectedProfiles().forEach(value -> append(key, value));
        List<String> roots = project.roots().stream().map(AnalysisFingerprint::rootKey).sorted().toList();
        append(key, "roots");
        append(key, Integer.toString(roots.size()));
        roots.forEach(value -> append(key, value));
        appendArtifacts(key, "classpath", project.classpath());
        appendArtifacts(key, "modulepath", project.modulepath());
        return key.toString();
    }

    private static String rootKey(AnalysisInputs.Root root) {
        StringBuilder key = new StringBuilder();
        append(key, "path");
        append(key, root.path());
        append(key, "kind");
        append(key, root.kind());
        append(key, "included");
        append(key, Boolean.toString(root.included()));
        append(key, "exclusions");
        append(key, Integer.toString(root.exclusions().size()));
        root.exclusions().forEach(value -> append(key, value));
        return key.toString();
    }

    private static void appendArtifacts(StringBuilder key, String label, List<AnalysisInputs.Artifact> artifacts) {
        append(key, label);
        append(key, Integer.toString(artifacts.size()));
        for (AnalysisInputs.Artifact artifact : artifacts) {
            append(key, Integer.toString(artifact.ordinal()));
            append(key, artifact.logicalId());
            append(key, artifact.kind());
            append(key, artifact.contentDigest());
            append(key, Long.toString(artifact.byteLength()));
        }
    }

    private static void append(StringBuilder output, String value) {
        output.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static final class CanonicalWriter {
        private final MessageDigest messageDigest;

        private CanonicalWriter(MessageDigest messageDigest) {
            this.messageDigest = messageDigest;
        }

        private void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            messageDigest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            messageDigest.update((byte) ':');
            messageDigest.update(bytes);
        }

        private void number(long value) {
            text(Long.toString(value));
        }
    }
}
