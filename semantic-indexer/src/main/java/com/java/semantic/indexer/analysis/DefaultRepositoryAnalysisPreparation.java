package com.java.semantic.indexer.analysis;

import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.adapter.jdtls.AnalysisWorkspaceKey;
import com.java.semantic.semantic.adapter.jdtls.JdtLsBuildWorkspaceStatus;
import com.java.semantic.semantic.adapter.jdtls.JdtLsEffectiveEnvironmentInspector;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceSession;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import com.java.semantic.semantic.adapter.jdtls.WorkspaceLease;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Coordinates a fresh lease, post-import attestation, and a bound semantic service. */
public final class DefaultRepositoryAnalysisPreparation implements RepositoryAnalysisPreparation {
    private static final String PROCESS_ANNOTATIONS =
            "org.eclipse.jdt.core.compiler.processAnnotations";

    private final JdtWorkspaceManager workspaceManager;
    private final JdtLsEffectiveEnvironmentInspector inspector;
    private final FullIndexPlanner planner;

    public DefaultRepositoryAnalysisPreparation(
            JdtWorkspaceManager workspaceManager,
            JdtLsEffectiveEnvironmentInspector inspector,
            FullIndexPlanner planner) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager is required");
        this.inspector = Objects.requireNonNull(inspector, "inspector is required");
        this.planner = Objects.requireNonNull(planner, "planner is required");
    }

    @Override
    public PreparedAnalysis prepare(AnalysisTarget target) {
        Objects.requireNonNull(target, "target is required");
        RepositorySnapshot snapshot = target.snapshot();
        WorkspaceLease lease = workspaceManager.acquire(new AnalysisWorkspaceKey(
                snapshot.repositoryId(), snapshot.revision(), target.jobId(), target.stage()), snapshot,
                target.managedCheckout());
        try {
            AnalysisInputs inputs = inspector.inspect(lease.session(), snapshot);
            AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
            FullIndexPlan plan = planner.plan(snapshot.root(), includedSourceRoots(snapshot, inputs),
                    effectiveCompilerOptions(inputs));
            Lsp4jJavaSemanticService semanticService = new Lsp4jJavaSemanticService(snapshot, lease.session());
            return new LeasePreparedAnalysis(snapshot, plan, fingerprint, readinessEvidence(
                    snapshot, inputs, fingerprint, semanticService, lease.session()),
                    semanticService, inspector, lease);
        } catch (RuntimeException exception) {
            lease.close();
            throw exception;
        }
    }

    private static SemanticAnalysisEvidence readinessEvidence(
            RepositorySnapshot snapshot,
            AnalysisInputs inputs,
            AnalysisFingerprint fingerprint,
            Lsp4jJavaSemanticService semanticService,
            JdtWorkspaceSession session) {
        List<SemanticAnalysisEvidence.ProjectProof> projects = new ArrayList<>();
        List<SemanticAnalysisEvidence.Limitation> limitations = new ArrayList<>();
        for (AnalysisInputs.Project project : inputs.projects()) {
            List<String> verifiedRoots = new ArrayList<>();
            for (AnalysisInputs.Root root : project.roots()) {
                if (!root.included()) {
                    limitations.add(new SemanticAnalysisEvidence.Limitation("ROOT_EXCLUDED_" + root.kind(),
                            Optional.of(root.path())));
                    continue;
                }
                if (containsJavaSource(snapshot.root(), root.path())) {
                    semanticService.proveImportedRoot(snapshot, snapshot.root().resolve(root.path()));
                    verifiedRoots.add(root.path());
                }
            }
            projects.add(new SemanticAnalysisEvidence.ProjectProof(project.projectPath(), true, verifiedRoots));
        }
        if (session.buildStatus() == JdtLsBuildWorkspaceStatus.WITH_ERROR) {
            limitations.add(new SemanticAnalysisEvidence.Limitation("BUILD_WITH_ERROR", Optional.empty()));
        }
        String buildStatus = session.buildStatus() == JdtLsBuildWorkspaceStatus.SUCCEED
                ? "SUCCESS"
                : session.buildStatus().name();
        return new SemanticAnalysisEvidence(
                IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                fingerprint.digest(), buildStatus, projects,
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), limitations);
    }

    private static boolean containsJavaSource(Path repositoryRoot, String repositoryRelativeRoot) {
        Path sourceRoot = repositoryRoot.resolve(repositoryRelativeRoot).normalize();
        if (!sourceRoot.startsWith(repositoryRoot.toAbsolutePath().normalize()) || !Files.isDirectory(sourceRoot)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".java"));
        } catch (IOException exception) {
            return false;
        }
    }

    static List<Path> includedSourceRoots(RepositorySnapshot snapshot, AnalysisInputs inputs) {
        List<Path> roots = new ArrayList<>();
        for (AnalysisInputs.Project project : inputs.projects()) {
            for (AnalysisInputs.Root root : project.roots()) {
                Path sourceRoot = snapshot.root().resolve(root.path()).normalize();
                if (root.included() && sourceRoot.startsWith(snapshot.root().toAbsolutePath().normalize())
                        && Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                    roots.add(sourceRoot);
                }
            }
        }
        return List.copyOf(roots);
    }

    static Map<String, String> effectiveCompilerOptions(AnalysisInputs inputs) {
        Map<String, String> options = new TreeMap<>();
        for (AnalysisInputs.Project project : inputs.projects()) {
            for (Map.Entry<String, String> option : project.compilerOptions().entrySet()) {
                if (PROCESS_ANNOTATIONS.equals(option.getKey())) {
                    continue;
                }
                String existing = options.putIfAbsent(option.getKey(), option.getValue());
                if (Objects.nonNull(existing) && !existing.equals(option.getValue())) {
                    throw new IllegalStateException("included projects disagree on compiler option " + option.getKey());
                }
            }
        }
        return Map.copyOf(options);
    }

    private static final class LeasePreparedAnalysis implements PreparedAnalysis {
        private final RepositorySnapshot snapshot;
        private final FullIndexPlan plan;
        private final AnalysisFingerprint fingerprint;
        private final SemanticAnalysisEvidence readinessEvidence;
        private final JavaSemanticService semanticService;
        private final JdtLsEffectiveEnvironmentInspector inspector;
        private final WorkspaceLease lease;

        private LeasePreparedAnalysis(
                RepositorySnapshot snapshot,
                FullIndexPlan plan,
                AnalysisFingerprint fingerprint,
                SemanticAnalysisEvidence readinessEvidence,
                JavaSemanticService semanticService,
                JdtLsEffectiveEnvironmentInspector inspector,
                WorkspaceLease lease) {
            this.snapshot = snapshot;
            this.plan = plan;
            this.fingerprint = fingerprint;
            this.readinessEvidence = readinessEvidence;
            this.semanticService = semanticService;
            this.inspector = inspector;
            this.lease = lease;
        }

        @Override
        public RepositorySnapshot snapshot() {
            return snapshot;
        }

        @Override
        public FullIndexPlan plan() {
            return plan;
        }

        @Override
        public AnalysisFingerprint fingerprint() {
            return fingerprint;
        }

        @Override
        public SemanticAnalysisEvidence readinessEvidence() {
            return readinessEvidence;
        }

        @Override
        public JavaSemanticService semanticService() {
            return semanticService;
        }

        @Override
        public void verifyUnchangedInputs() {
            AnalysisInputs current = inspector.inspect(lease.session(), snapshot);
            if (!fingerprint.equals(AnalysisFingerprint.from(current))) {
                throw new IllegalStateException("effective semantic analysis inputs changed during preparation");
            }
        }

        @Override
        public void close() {
            lease.close();
        }
    }
}
