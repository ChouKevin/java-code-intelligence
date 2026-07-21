package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.CallGraphBuildResult;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisMetadata;
import com.java.semantic.callgraph.domain.AnalysisStatus;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.identity.PolicyIdentity;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.RevisionBoundAnalysisResult;
import com.java.semantic.config.CallGraphDepthProperties;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 在單一儲存庫讀鎖內協調版本綁定的語意分析 */
public final class SemanticAnalysisApplicationService {

    private static final String POLICY_MESSAGE =
            "Business policy prohibits reading or summarizing this target";
    private static final String FAILURE_MESSAGE = "Analysis could not be completed";

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final JavaSemanticService semanticService;
    private final SemanticCallGraphBuilder builder;
    private final ReadPolicy readPolicy;
    private final int maxDepth;

    public SemanticAnalysisApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            JavaSemanticService semanticService,
            SemanticCallGraphBuilder builder,
            ReadPolicy readPolicy,
            CallGraphDepthProperties depthProperties) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.builder = Objects.requireNonNull(builder, "builder is required");
        this.readPolicy = Objects.requireNonNull(readPolicy, "readPolicy is required");
        this.maxDepth = Objects.requireNonNull(depthProperties, "depthProperties is required")
                .callGraphDepth();
    }

    public RevisionBoundAnalysisResult<ExplainableCallGraph> analyze(
            RepositoryId repositoryId,
            Optional<RepositoryRevision> expectedRevision,
            String packageName,
            String className,
            String methodSignature) {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        try {
            return repositoryApplicationService.withSnapshot(
                    repositoryId,
                    expectedRevision,
                    snapshot -> analyzeSnapshot(
                            snapshot, packageName, className, methodSignature));
        } catch (RepositoryRevisionMismatchException exception) {
            return failed(repositoryId, exception.getCurrentRevision());
        }
    }

    private RevisionBoundAnalysisResult<ExplainableCallGraph> analyzeSnapshot(
            RepositorySnapshot snapshot,
            String packageName,
            String className,
            String methodSignature) {
        AnalysisMetadata metadata = metadata(snapshot.repositoryId());
        RepositoryRevision revision = snapshot.revision();
        try {
            if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                    readPolicy.visibilityOfRepository(snapshot.repositoryId().value()))) {
                return forbidden(metadata, revision);
            }
            SemanticMethod root = semanticService.resolveMethod(
                    snapshot, packageName, className, methodSignature);
            if (EvidenceVisibility.BUSINESS_READ_FORBIDDEN.equals(
                    readPolicy.visibilityOf(methodId(snapshot.repositoryId(), root)))) {
                return forbidden(metadata, revision);
            }
            RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
            CallGraphBuildResult buildResult = builder.build(
                    snapshot, syntax, root, maxDepth);
            return result(buildResult, metadata, revision);
        } catch (RuntimeException exception) {
            return failed(metadata, revision);
        }
    }

    private RevisionBoundAnalysisResult<ExplainableCallGraph> result(
            CallGraphBuildResult buildResult,
            AnalysisMetadata metadata,
            RepositoryRevision revision) {
        if (buildResult.partial()) {
            return RevisionBoundAnalysisResult.partial(
                    buildResult.graph(),
                    buildResult.warnings(),
                    buildResult.errors(),
                    metadata,
                    revision.value());
        }
        return new RevisionBoundAnalysisResult<>(
                AnalysisStatus.SUCCESS,
                buildResult.graph(),
                buildResult.warnings(),
                buildResult.errors(),
                metadata,
                revision.value());
    }

    private RevisionBoundAnalysisResult<ExplainableCallGraph> forbidden(
            AnalysisMetadata metadata,
            RepositoryRevision revision) {
        AnalysisWarning warning = new AnalysisWarning(
                "BUSINESS_READ_FORBIDDEN", POLICY_MESSAGE, "");
        return RevisionBoundAnalysisResult.businessReadForbidden(
                List.of(warning), metadata, revision.value());
    }

    private RevisionBoundAnalysisResult<ExplainableCallGraph> failed(
            RepositoryId repositoryId,
            RepositoryRevision revision) {
        return failed(metadata(repositoryId), revision);
    }

    private RevisionBoundAnalysisResult<ExplainableCallGraph> failed(
            AnalysisMetadata metadata,
            RepositoryRevision revision) {
        AnalysisError error = new AnalysisError("ANALYSIS_FAILED", FAILURE_MESSAGE, "");
        return RevisionBoundAnalysisResult.failed(List.of(error), metadata, revision.value());
    }

    private AnalysisMetadata metadata(RepositoryId repositoryId) {
        return new AnalysisMetadata(repositoryId.value(), Instant.now());
    }

    private MethodId methodId(RepositoryId repositoryId, SemanticMethod method) {
        return new MethodId(
                repositoryId.value(),
                method.packageName(),
                PolicyIdentity.className(method.packageName(), method.className()),
                method.methodName(),
                PolicyIdentity.parameterTypes(method.parameterTypes()));
    }
}
