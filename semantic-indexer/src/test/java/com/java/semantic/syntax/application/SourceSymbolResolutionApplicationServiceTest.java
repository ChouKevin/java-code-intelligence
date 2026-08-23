package com.java.semantic.syntax.application;

import com.java.semantic.config.SourceSymbolResolutionProperties;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.SourceRange;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** snapshot authority、bounded retries 與 executable follow-up orchestration 契約 */
class SourceSymbolResolutionApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("order-service");
    private static final RepositoryRevision EXPECTED = RepositoryRevision.ofSha("a".repeat(40));
    private static final RepositoryRevision ANALYZED = RepositoryRevision.ofSha("b".repeat(40));
    private static final String SOURCE_FILE = "src/main/java/com/acme/OrderService.java";

    @Test
    void should_use_snapshot_revision_and_bound_complete_context_retries() {
        MethodTarget noArgs = target(List.of());
        MethodTarget textArg = target(List.of("java.lang.String"));
        MethodTarget orderArg = target(List.of("com.acme.Order"));
        SourceSymbolResolver resolver = (snapshot, query) -> new SourceSymbolResolution(
                SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT,
                List.of(
                        new SourceMethodContextCandidate(noArgs),
                        new SourceMethodContextCandidate(textArg),
                        new SourceMethodContextCandidate(orderArg)),
                List.of(),
                List.of(),
                Optional.empty(),
                0,
                0);
        RecordingRepositoryService repositories = new RecordingRepositoryService(ANALYZED);
        SourceSymbolResolutionApplicationService service = new SourceSymbolResolutionApplicationService(
                repositories, resolver, new DiscoveryFollowUpFactory(), new SourceSymbolResolutionProperties(2));
        SourceSymbolResolutionQuery query = query(
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        Optional.empty(),
                        Optional.of(new SourceSymbolContext.MethodContext("work", Optional.empty()))),
                "value",
                Optional.of(new SyntaxPosition(9, 4)));

        RevisionBoundSourceSymbolResolution result = service.resolve(query);

        assertThat(repositories.expectedRevision()).hasValue(EXPECTED);
        assertThat(result.analyzedRevision()).isEqualTo(ANALYZED);
        assertThat(result.status()).isEqualTo(SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT);
        assertThat(result.contextCandidateLimits()).isEqualTo(new SourceContextCandidateLimits(2, 2, 3, true));
        assertThat(result.contextCandidates()).hasSize(2).allSatisfy(candidate -> {
            SourceMethodContextCandidate method = (SourceMethodContextCandidate) candidate.candidate();
            DiscoveryFollowUp followUp = candidate.retry();
            assertThat(followUp.operation()).isEqualTo(DiscoveryFollowUp.Operation.RESOLVE_SOURCE_SYMBOL);
            DiscoveryFollowUp.ResolveSourceSymbolRequest retry =
                    (DiscoveryFollowUp.ResolveSourceSymbolRequest) followUp.request();
            assertThat(retry.context().sourceFile()).hasValue(method.target().sourceFile());
            assertThat(retry.context().method()).hasValueSatisfying(context -> {
                assertThat(context.name()).isEqualTo(method.target().methodName());
                assertThat(context.parameterTypes()).hasValue(method.target().parameterTypes());
            });
            assertThat(retry.position()).isEmpty();
        });
        assertThat(result.contextCandidates())
                .extracting(candidate -> ((SourceMethodContextCandidate) candidate.candidate())
                        .target().parameterTypes())
                .containsExactly(List.of(), List.of("java.lang.String"));
    }

    @Test
    void should_make_type_candidate_symbol_retry_and_resolved_method_followups_executable() {
        MethodTarget method = target(List.of("com.acme.Order"));
        SourceRange declaration = range(3, 4, 3, 20);
        SourceRange occurrence = range(8, 12, 8, 16);
        SourceSymbolResolver resolver = (snapshot, query) -> switch (query.symbol()) {
            case "type" -> new SourceSymbolResolution(
                    SourceSymbolResolutionStatus.AMBIGUOUS_CONTEXT,
                    List.of(
                            new SourceTypeContextCandidate("src/generated/java/com/acme/OrderService.java"),
                            new SourceTypeContextCandidate(SOURCE_FILE)),
                    List.of(), List.of(), Optional.empty(), 0, 0);
            case "pick" -> new SourceSymbolResolution(
                    SourceSymbolResolutionStatus.AMBIGUOUS_SYMBOL,
                    List.of(),
                    List.of(new SourceSymbolCandidate.Method(
                            method, declaration, occurrence, 1)),
                    List.of(), Optional.of(SOURCE_FILE), 100, 1);
            default -> new SourceSymbolResolution(
                    SourceSymbolResolutionStatus.RESOLVED,
                    List.of(),
                    List.of(new SourceSymbolCandidate.Method(
                            method, declaration, occurrence, 1)),
                    List.of(), Optional.of(SOURCE_FILE), 100, 1);
        };
        SourceSymbolResolutionApplicationService service = new SourceSymbolResolutionApplicationService(
                new RecordingRepositoryService(ANALYZED),
                resolver,
                new DiscoveryFollowUpFactory(),
                new SourceSymbolResolutionProperties(100));

        RevisionBoundSourceSymbolResolution types = service.resolve(query(
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "OrderService"), Optional.empty(), Optional.empty()),
                "type", Optional.of(new SyntaxPosition(2, 2))));
        RevisionBoundSourceSymbolResolution ambiguous = service.resolve(query(
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "OrderService"), Optional.empty(), Optional.empty()),
                "pick", Optional.empty()));
        RevisionBoundSourceSymbolResolution resolved = service.resolve(query(
                new SourceSymbolContext(
                        new JavaTypeIdentity("com.acme", "OrderService"), Optional.empty(), Optional.empty()),
                "confirm", Optional.empty()));

        assertThat(types.contextCandidates()).allSatisfy(candidate -> {
            SourceTypeContextCandidate type = (SourceTypeContextCandidate) candidate.candidate();
            DiscoveryFollowUp.ResolveSourceSymbolRequest retry = (DiscoveryFollowUp.ResolveSourceSymbolRequest)
                    candidate.retry().request();
            assertThat(retry.context().sourceFile()).hasValue(type.sourceFile());
            assertThat(retry.position()).isEmpty();
        });
        DiscoveryFollowUp.ResolveSourceSymbolRequest symbolRetry = (DiscoveryFollowUp.ResolveSourceSymbolRequest)
                ambiguous.candidates().getFirst().availableFollowUps().getFirst().request();
        assertThat(symbolRetry.context().sourceFile()).hasValue(SOURCE_FILE);
        assertThat(symbolRetry.position()).hasValue(occurrence.range().start());
        assertThat(resolved.candidates().getFirst().availableFollowUps())
                .extracting(DiscoveryFollowUp::operation)
                .containsExactly(
                        DiscoveryFollowUp.Operation.GET_METHOD_SOURCE,
                        DiscoveryFollowUp.Operation.ANALYZE_OUTGOING_CALL_GRAPH,
                        DiscoveryFollowUp.Operation.ANALYZE_INCOMING_CALL_GRAPH);
    }

    private SourceSymbolResolutionQuery query(
            SourceSymbolContext context,
            String symbol,
            Optional<SyntaxPosition> position) {
        return new SourceSymbolResolutionQuery(REPOSITORY_ID, EXPECTED, context, symbol, position);
    }

    private MethodTarget target(List<String> parameterTypes) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        SOURCE_FILE),
                "work",
                parameterTypes);
    }

    private SourceRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SourceRange(SOURCE_FILE, new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter)));
    }

    private static final class RecordingRepositoryService implements RepositoryApplicationService {

        private final RepositorySnapshot snapshot;

        private Optional<RepositoryRevision> expectedRevision = Optional.empty();

        private RecordingRepositoryService(RepositoryRevision revision) {
            snapshot = new RepositorySnapshot(REPOSITORY_ID, Path.of("."), revision);
        }

        private Optional<RepositoryRevision> expectedRevision() {
            return expectedRevision;
        }

        @Override
        public <T> T withSnapshot(
                RepositoryId repositoryId,
                Optional<RepositoryRevision> expectedRevision,
                Function<RepositorySnapshot, T> operation) {
            this.expectedRevision = expectedRevision;
            return operation.apply(snapshot);
        }

        @Override
        public RepositoryStatus status(RepositoryId repositoryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryStatus> list() {
            throw new UnsupportedOperationException();
        }
    }
}
