package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.CallGraphBuildResult;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.EvidenceVisibility;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.ReadPolicy;
import com.java.semantic.callgraph.domain.TypeId;
import com.java.semantic.callgraph.domain.RevisionBoundAnalysisResult;
import com.java.semantic.config.CallGraphDepthProperties;
import com.java.semantic.repository.application.DefaultRepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.repository.port.GitRepositoryPort;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticAnalysisConcurrencyTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");
    private static final String ORIGINAL_CONTENT = "revision-one-content";
    private static final String MUTATED_CONTENT = "revision-two-content";

    @TempDir
    private Path tempDirectory;

    @Test
    void should_block_mutation_and_report_observed_revision_when_builder_holds_snapshot_lock()
            throws Exception {
        BlockingGitRepositoryPort gitRepositoryPort = new BlockingGitRepositoryPort();
        DefaultRepositoryApplicationService repositoryService = repositoryService(gitRepositoryPort);
        repositoryService.ensure(REPOSITORY_ID);
        CountDownLatch builderStarted = new CountDownLatch(1);
        CountDownLatch builderMayFinish = new CountDownLatch(1);
        AtomicReference<String> observedContent = new AtomicReference<>();
        SemanticCallGraphBuilder builder = blockingBuilder(
                builderStarted, builderMayFinish, observedContent);
        SemanticAnalysisApplicationService service = analysisService(repositoryService, builder);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RevisionBoundAnalysisResult<ExplainableCallGraph>> analysis = pool.submit(() -> service.analyze(
                    REPOSITORY_ID,
                    Optional.of(SHA_ONE),
                    "com.acme",
                    "OrderService",
                    "place(Order)"));
            assertThat(builderStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch mutationStarted = new CountDownLatch(1);
            Future<RepositoryStatus> mutation = pool.submit(() -> {
                mutationStarted.countDown();
                return repositoryService.sync(REPOSITORY_ID, Optional.empty());
            });

            assertThat(mutationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(mutation.isDone()).isFalse();
            assertThatThrownBy(() -> mutation.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(gitRepositoryPort.fetchEntered()).isFalse();

            builderMayFinish.countDown();
            RevisionBoundAnalysisResult<ExplainableCallGraph> result = analysis.get(5, TimeUnit.SECONDS);
            assertThat(result.analyzedRevision()).isEqualTo(SHA_ONE.value());
            assertThat(observedContent.get()).isEqualTo(ORIGINAL_CONTENT);

            assertThat(mutation.get(5, TimeUnit.SECONDS).currentRevision()).contains(SHA_TWO);
            assertThat(gitRepositoryPort.fetchEntered()).isTrue();
            assertThat(Files.readString(
                    tempDirectory.resolve(REPOSITORY_ID.value()).resolve("OrderService.java")))
                    .isEqualTo(MUTATED_CONTENT);
        } finally {
            builderMayFinish.countDown();
            pool.shutdownNow();
        }
    }

    private SemanticCallGraphBuilder blockingBuilder(
            CountDownLatch started,
            CountDownLatch mayFinish,
            AtomicReference<String> observedContent) {
        SemanticCallGraphBuilder builder = mock(SemanticCallGraphBuilder.class);
        when(builder.build(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    RepositorySnapshot snapshot = invocation.getArgument(0);
                    try {
                        observedContent.set(Files.readString(snapshot.root().resolve("OrderService.java")));
                    } catch (IOException exception) {
                        throw new IllegalStateException("fixture read failed", exception);
                    }
                    started.countDown();
                    await(mayFinish);
                    return new CallGraphBuildResult(graph(), List.of(), List.of(), false);
                });
        return builder;
    }

    private DefaultRepositoryApplicationService repositoryService(GitRepositoryPort gitRepositoryPort) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(tempDirectory.toString());
        properties.setRepositoryLockTimeout(Duration.ofSeconds(3));
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl("https://example.invalid/orders.git");
        properties.getRepositories().put(REPOSITORY_ID.value(), config);
        RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(properties);
        return new DefaultRepositoryApplicationService(
                registry, gitRepositoryPort, List.of(), List.of(), properties);
    }

    private SemanticAnalysisApplicationService analysisService(
            DefaultRepositoryApplicationService repositoryService,
            SemanticCallGraphBuilder builder) {
        SemanticMethod root = method();
        JavaSemanticService semanticService = new JavaSemanticService() {
            @Override
            public SemanticMethod resolveMethod(
                    RepositorySnapshot snapshot,
                    String packageName,
                    String className,
                    String methodSignature) {
                return root;
            }

            @Override
            public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
                return List.of();
            }

            @Override
            public Optional<SemanticCall> resolveCallAt(
                    RepositorySnapshot snapshot, SemanticMethod caller, SemanticCallSite callSite) {
                return Optional.empty();
            }

            @Override
            public List<SemanticMethod> implementations(
                    RepositorySnapshot snapshot, SemanticMethod method) {
                return List.of();
            }
        };
        SyntaxExtractionService syntaxService = rootPath -> RepositorySyntax.empty();
        ReadPolicy readable = new ReadPolicy() {
            @Override
            public EvidenceVisibility visibilityOfRepository(String repositoryId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(TypeId typeId) {
                return EvidenceVisibility.READABLE;
            }

            @Override
            public EvidenceVisibility visibilityOf(MethodId methodId) {
                return EvidenceVisibility.READABLE;
            }
        };
        return new SemanticAnalysisApplicationService(
                repositoryService,
                syntaxService,
                semanticService,
                builder,
                readable,
                new CallGraphDepthProperties(3));
    }

    private static SemanticMethod method() {
        SemanticPosition start = new SemanticPosition(0, 0);
        SemanticPosition end = new SemanticPosition(2, 1);
        SemanticRange range = new SemanticRange(start, end);
        return new SemanticMethod(
                "com.acme",
                "OrderService",
                "place",
                List.of("Order"),
                "void",
                new SemanticLocation("file:///orders/OrderService.java", range, range));
    }

    private static ExplainableCallGraph graph() {
        MethodId root = new MethodId(
                REPOSITORY_ID.value(), "com.acme", "OrderService", "place", List.of("Order"));
        return new ExplainableCallGraph(
                root,
                List.of(),
                List.of(),
                Map.of(),
                new FlattenedCallGraph(List.of(), "place(Order)", Map.of()));
    }

    private final class BlockingGitRepositoryPort implements GitRepositoryPort {

        private volatile boolean fetchEntered;

        @Override
        public boolean isCloned(Path workingTree) {
            return Files.exists(workingTree.resolve("OrderService.java"));
        }

        @Override
        public RepositoryRevision clone(Path workingTree, String url, String branch) {
            write(workingTree.resolve("OrderService.java"), ORIGINAL_CONTENT);
            return SHA_ONE;
        }

        @Override
        public RepositoryRevision fetchAndReset(Path workingTree, String branch) {
            fetchEntered = true;
            write(workingTree.resolve("OrderService.java"), MUTATED_CONTENT);
            return SHA_TWO;
        }

        @Override
        public RepositoryRevision checkout(Path workingTree, String revision) {
            return SHA_TWO;
        }

        @Override
        public RepositoryRevision currentRevision(Path workingTree) {
            return SHA_ONE;
        }

        @Override
        public String currentBranch(Path workingTree) {
            return "main";
        }

        private boolean fetchEntered() {
            return fetchEntered;
        }

        private void write(Path path, String content) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, content);
            } catch (IOException exception) {
                throw new IllegalStateException("fixture write failed", exception);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch interrupted", exception);
        }
    }
}
